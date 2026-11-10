package dev.andrii.battery

import java.util.UUID
import kotlin.math.abs

/** One reading from the battery's BMS. [current] is positive while charging, negative while discharging. */
data class BmsStatus(
    val voltage: Double,
    val current: Double,
    val soc: Int,
    val remainingAh: Double,
    val capacityAh: Double,
    val cycles: Int,
    val chargeOn: Boolean,
    val dischargeOn: Boolean,
    val tempsC: List<Double>,
    val cellsMv: List<Int>,
    val protections: List<String>,
)

/**
 * The JBD ("Xiaoxiang") BMS protocol the battery speaks: requests are written to
 * characteristic FF02, replies arrive as notifications on FF01, possibly split
 * across several notifications.
 */
object Bms {
    val SERVICE: UUID = UUID.fromString("0000ff00-0000-1000-8000-00805f9b34fb")
    val RX: UUID = UUID.fromString("0000ff01-0000-1000-8000-00805f9b34fb")
    val TX: UUID = UUID.fromString("0000ff02-0000-1000-8000-00805f9b34fb")
    const val BASIC = 0x03
    const val CELLS = 0x04

    private val PROTECTIONS = listOf(
        "cell overvoltage", "cell undervoltage", "pack overvoltage", "pack undervoltage",
        "charge overtemperature", "charge undertemperature", "discharge overtemperature",
        "discharge undertemperature", "charge overcurrent", "discharge overcurrent",
        "short circuit", "front-end chip error", "output locked by software",
    )

    /** Read request for [cmd]: `DD A5 cmd 00 chk chk 77`. */
    fun request(cmd: Int): ByteArray {
        val chk = -cmd and 0xFFFF
        return byteArrayOf(0xDD.toByte(), 0xA5.toByte(), cmd.toByte(), 0, (chk shr 8).toByte(), chk.toByte(), 0x77)
    }

    /** True once [buf] holds at least one whole reply frame. */
    fun complete(buf: ByteArray) = buf.size >= 7 && buf.size >= u8(buf, 3) + 7

    /** Data bytes of a reply `DD cmd status len data.. chk chk 77`; throws if the frame is malformed. */
    fun payload(frame: ByteArray, cmd: Int): ByteArray {
        val n = u8(frame, 3)
        require(u8(frame, 0) == 0xDD && u8(frame, 1) == cmd && u8(frame, 2) == 0 && u8(frame, 6 + n) == 0x77) {
            "Malformed reply to command $cmd"
        }
        val sum = (2 until 4 + n).sumOf { u8(frame, it) }
        require(u16(frame, 4 + n) == -sum and 0xFFFF) { "Bad checksum in reply to command $cmd" }
        return frame.copyOfRange(4, 4 + n)
    }

    /** Decodes the payloads of the [BASIC] and [CELLS] replies; throws if the pack voltage isn't the sum of its cells. */
    fun decode(basic: ByteArray, cells: ByteArray): BmsStatus {
        val flags = u16(basic, 16)
        val status = BmsStatus(
            voltage = u16(basic, 0) / 100.0,
            current = (u16(basic, 2).toShort()) / 100.0,
            soc = u8(basic, 19),
            remainingAh = u16(basic, 4) / 100.0,
            capacityAh = u16(basic, 6) / 100.0,
            cycles = u16(basic, 8),
            chargeOn = basic[20].toInt() and 1 != 0,
            dischargeOn = basic[20].toInt() and 2 != 0,
            tempsC = List(u8(basic, 22)) { (u16(basic, 23 + 2 * it) - 2731) / 10.0 },
            cellsMv = List(cells.size / 2) { u16(cells, 2 * it) },
            protections = PROTECTIONS.filterIndexed { bit, _ -> flags shr bit and 1 != 0 },
        )
        // The battery now and then answers with a well-formed block of nonsense, such as 2.56 V
        // across 32 cells at 2%; real readings stay within a few percent of their cells' sum.
        require(abs(status.cellsMv.sum() / 1000.0 - status.voltage) <= status.voltage * 0.1) {
            "Pack voltage ${status.voltage} V doesn't match its cells ${status.cellsMv}"
        }
        return status
    }

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
    private fun u16(b: ByteArray, i: Int) = u8(b, i) shl 8 or u8(b, i + 1)
}
