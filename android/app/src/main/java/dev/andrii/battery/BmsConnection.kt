package dev.andrii.battery

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withTimeoutOrNull

/** A failure worth showing as is: its message is written for the person holding the phone. */
class BatteryProblem(message: String) : Exception(message)

/** A battery heard advertising the BMS service; [rssi] is its signal strength in dBm. */
data class Nearby(val address: String, val name: String, val rssi: Int)

private const val DROPPED = "Lost the connection to the battery."
private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

/**
 * An open GATT connection to the battery. The BMS accepts a single connection
 * at a time, so close it as soon as it is not needed.
 */
// MainActivity starts polling only once BLUETOOTH_SCAN and BLUETOOTH_CONNECT are
// granted; a permission revoked later surfaces as a SecurityException.
@SuppressLint("MissingPermission")
class BmsConnection private constructor(
    private val gatt: BluetoothGatt,
    private val tx: BluetoothGattCharacteristic,
    private val frames: Channel<ByteArray>,
) : AutoCloseable {

    /**
     * Reads the current status. A lost, garbled or self-contradicting reply is
     * retried twice on the same connection, since this battery's Bluetooth drops
     * packets now and then and the battery itself occasionally reports nonsense;
     * a dropped connection fails at once.
     */
    suspend fun read(): BmsStatus {
        var attempt = 0
        while (true) {
            try {
                return Bms.decode(query(Bms.BASIC), query(Bms.CELLS))
            } catch (e: Exception) {
                if (e is CancellationException || frames.isClosedForReceive || ++attempt == 3) throw e
            }
        }
    }

    private suspend fun query(cmd: Int): ByteArray {
        while (frames.tryReceive().isSuccess) Unit // leftovers of an earlier, timed-out reply
        val request = Bms.request(cmd)
        if (Build.VERSION.SDK_INT >= 33) {
            gatt.writeCharacteristic(tx, request, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
        } else {
            tx.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            @Suppress("DEPRECATION")
            tx.value = request
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(tx)
        }
        val frame = withTimeoutOrNull(3_000) {
            var buf = ByteArray(0)
            while (!Bms.complete(buf)) buf += frames.receive()
            buf
        } ?: throw BatteryProblem("The battery stopped answering.")
        return Bms.payload(frame, cmd)
    }

    override fun close() {
        gatt.disconnect()
        gatt.close()
    }

    companion object {
        /** Finds the battery with Bluetooth [address] and connects to it. */
        suspend fun open(context: Context, address: String): BmsConnection {
            val scanner = scanner(context) ?: throw BatteryProblem("Bluetooth is off. Turn it on to reach the battery.")
            val device = scan(scanner, address)

            val ready = Channel<Unit>(Channel.UNLIMITED)
            val frames = Channel<ByteArray>(Channel.UNLIMITED)
            val gatt = device.connectGatt(context, false, object : BluetoothGattCallback() {
                override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        gatt.discoverServices()
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        ready.close(BatteryProblem(DROPPED))
                        frames.close(BatteryProblem(DROPPED))
                    }
                }

                override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                    ready.trySend(Unit)
                }

                override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                    ready.trySend(Unit)
                }

                override fun onCharacteristicChanged(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    value: ByteArray,
                ) {
                    frames.trySend(value)
                }

                // Android 12 delivers notifications only through this older overload.
                @Deprecated("Replaced by the overload with a value parameter in API 33")
                override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                    @Suppress("DEPRECATION")
                    frames.trySend(characteristic.value)
                }
            }, BluetoothDevice.TRANSPORT_LE)

            try {
                val tx = withTimeoutOrNull(20_000) {
                    ready.receive() // services discovered
                    val service = gatt.getService(Bms.SERVICE)
                        ?: throw BatteryProblem("This device doesn't look like the battery.")
                    val rx = service.getCharacteristic(Bms.RX)
                    gatt.setCharacteristicNotification(rx, true)
                    val cccd = rx.getDescriptor(CCCD)
                    val enable = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    if (Build.VERSION.SDK_INT >= 33) {
                        gatt.writeDescriptor(cccd, enable)
                    } else {
                        @Suppress("DEPRECATION")
                        cccd.value = enable
                        @Suppress("DEPRECATION")
                        gatt.writeDescriptor(cccd)
                    }
                    ready.receive() // notifications enabled
                    service.getCharacteristic(Bms.TX)
                } ?: throw BatteryProblem("Found the battery but couldn't connect. Try moving closer.")
                return BmsConnection(gatt, tx, frames)
            } catch (e: Throwable) {
                gatt.disconnect()
                gatt.close()
                throw e
            }
        }

        /**
         * Every advertisement from a battery in range, until collection stops. Ends at once
         * while Bluetooth is off; fails with SecurityException without the scan permission.
         */
        fun nearby(context: Context): Flow<Nearby> = callbackFlow {
            val scanner = scanner(context)
            if (scanner == null) {
                close()
                return@callbackFlow
            }
            val callback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    val address = result.device.address
                    trySend(Nearby(address, result.scanRecord?.deviceName ?: address, result.rssi))
                }

                override fun onScanFailed(errorCode: Int) {
                    close(BatteryProblem("Bluetooth scan failed (error $errorCode)."))
                }
            }
            scanner.startScan(
                listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(Bms.SERVICE)).build()),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
                callback,
            )
            awaitClose { runCatching { scanner.stopScan(callback) } }
        }

        private fun scanner(context: Context): BluetoothLeScanner? =
            context.getSystemService(BluetoothManager::class.java).adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner

        private suspend fun scan(scanner: BluetoothLeScanner, address: String): BluetoothDevice {
            val found = CompletableDeferred<BluetoothDevice>()
            val callback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    found.complete(result.device)
                }

                override fun onScanFailed(errorCode: Int) {
                    found.completeExceptionally(BatteryProblem("Bluetooth scan failed (error $errorCode)."))
                }
            }
            scanner.startScan(
                listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(Bms.SERVICE)).setDeviceAddress(address).build()),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
                callback,
            )
            try {
                return withTimeoutOrNull(15_000) { found.await() } ?: throw BatteryProblem(
                    "Can't find the battery. It may be out of range, or another phone may be connected to it.",
                )
            } finally {
                // Throws if Bluetooth was switched off meanwhile; the scan is gone either way.
                runCatching { scanner.stopScan(callback) }
            }
        }
    }
}
