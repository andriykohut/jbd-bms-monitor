package dev.andrii.battery

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

enum class Mode { Charging, Discharging, Idle }

/** A reading with its interpretation; [minutes] is time to empty or to full, null when idle or not yet known. */
data class Reading(val status: BmsStatus, val mode: Mode, val minutes: Int?, val at: Long) {
    val watts get() = (status.voltage * abs(status.current)).roundToInt()
}

/** [problem] is set while the battery can't be read; [reading] then holds the last good one, if any. */
data class MonitorState(val reading: Reading? = null, val problem: String? = null)

/** The problem shown from a fresh connection attempt until its first reading. */
const val SEARCHING = "Looking for the battery…"

/** The problem shown while no battery is chosen. */
const val NO_BATTERY = "No battery chosen. Open the app to pick one."

/** A battery the person has added: its Bluetooth address and the name it advertises. */
data class Battery(val address: String, val name: String)

private const val BATTERIES_KEY = "batteries"
private const val CURRENT_KEY = "battery"

/** "3 h 29 m" or "34 m". */
fun formatMinutes(minutes: Int) = if (minutes >= 60) "${minutes / 60} h ${minutes % 60} m" else "$minutes m"

/** "3 h 29 m left", "1 h 11 m to full", or the mode in lower case when there is no estimate. */
fun summary(reading: Reading): String = when {
    reading.minutes == null -> reading.mode.name.lowercase()
    reading.mode == Mode.Charging -> "${formatMinutes(reading.minutes)} to full"
    else -> "${formatMinutes(reading.minutes)} left"
}

/** "Discharging at 241 W", "Charging at 208 W", or "Idle". */
fun flowText(reading: Reading): String =
    if (reading.mode == Mode.Idle) "Idle" else "${reading.mode.name} at ${reading.watts} W"

/**
 * Polls the [current] battery for the whole app. While the screen is visible it holds
 * the connection and reads every 2 seconds; while only [WatchService] runs it connects
 * once a minute and lets go in between, so other phones can reach the battery.
 */
object Monitor {
    val state = MutableStateFlow(MonitorState(problem = SEARCHING))
    val uiVisible = MutableStateFlow(false)
    val watching = MutableStateFlow(false)
    /** Batteries the person has added, by name; saved across restarts. */
    val batteries = MutableStateFlow<List<Battery>>(emptyList())
    /** The battery to poll; null until one is chosen. */
    val current = MutableStateFlow<Battery?>(null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var demo = false
    private var prefs: SharedPreferences? = null
    private val samples = ArrayDeque<Pair<Long, Double>>()

    /** Loads the saved batteries on the first call; later calls do nothing. */
    @Synchronized
    fun load(context: Context) {
        if (prefs != null) return
        val saved = context.applicationContext.getSharedPreferences("battery", Context.MODE_PRIVATE)
        prefs = saved
        // Each entry is "address name"; an address never contains a space.
        batteries.value = saved.getStringSet(BATTERIES_KEY, emptySet())!!
            .map { Battery(it.substringBefore(' '), it.substringAfter(' ')) }
            .sortedBy { it.name }
        current.value = batteries.value.find { it.address == saved.getString(CURRENT_KEY, null) }
        if (current.value == null) state.value = MonitorState(problem = NO_BATTERY)
    }

    /** Starts polling unless it already runs; polling stops by itself once neither the screen nor the watcher needs it. */
    @Synchronized
    fun ensureRunning(context: Context) {
        load(context)
        if (demo || job?.isActive == true || current.value == null) return
        restart(context) { it }
    }

    /** Adds [battery] unless already added, and switches polling over to it. */
    @Synchronized
    fun connect(context: Context, battery: Battery) {
        if (demo) return
        if (battery.address !in batteries.value.map { it.address }) batteries.value = (batteries.value + battery).sortedBy { it.name }
        current.value = battery
        save()
        restart(context) { MonitorState(problem = SEARCHING) }
    }

    /** Removes [battery]; forgetting the current one also disconnects from it. */
    @Synchronized
    fun forget(context: Context, battery: Battery) {
        if (demo) return
        batteries.value -= battery
        if (current.value == battery) {
            current.value = null
            restart(context) { MonitorState(problem = NO_BATTERY) }
        }
        save()
    }

    /** Drops the connection and connects again at once; the last reading stays on screen meanwhile. */
    @Synchronized
    fun refresh(context: Context) {
        if (demo || current.value == null) return
        restart(context) { it.copy(problem = SEARCHING) }
    }

    private fun save() = prefs!!.edit {
        putStringSet(BATTERIES_KEY, batteries.value.map { "${it.address} ${it.name}" }.toSet())
        putString(CURRENT_KEY, current.value?.address)
    }

    /**
     * Replaces the polling job. [reset] applies to the state only once the old job has
     * closed its connection, so a late reading from it can't overwrite the reset.
     */
    private fun restart(context: Context, reset: (MonitorState) -> MonitorState) {
        val old = job
        val appContext = context.applicationContext
        job = scope.launch {
            old?.cancelAndJoin()
            samples.clear()
            state.update(reset)
            current.value?.let { poll(appContext, it.address) }
        }
    }

    private suspend fun poll(context: Context, address: String) {
        while (uiVisible.value || watching.value) {
            // In the background: the next read in a minute, or in 10 s after a failure.
            var wait = 60_000L
            try {
                BmsConnection.open(context, address).use { connection ->
                    do {
                        val status = connection.read()
                        state.value = MonitorState(interpret(status, System.currentTimeMillis()))
                        delay(2_000)
                    } while (uiVisible.value)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state.update { it.copy(problem = (e as? BatteryProblem)?.message ?: "Lost the connection to the battery.") }
                delay(3_000) // keeps a failing connection from turning into back-to-back scans
                wait = 7_000
            }
            withTimeoutOrNull(wait) { uiVisible.first { it } }
        }
    }

    private fun interpret(status: BmsStatus, now: Long): Reading {
        // Current averaged over the last minute, so one spike doesn't swing the estimate.
        samples.addLast(now to status.current)
        while (samples.first().first < now - 60_000) samples.removeFirst()
        val average = samples.sumOf { it.second } / samples.size
        val mode = when {
            status.current > 0.1 -> Mode.Charging
            status.current < -0.1 -> Mode.Discharging
            else -> Mode.Idle
        }
        val hours = when {
            mode == Mode.Charging && average > 0.1 -> (status.capacityAh - status.remainingAh) / average
            mode == Mode.Discharging && average < -0.1 -> status.remainingAh / -average
            else -> null
        }
        return Reading(status, mode, hours?.let { (it * 60).roundToInt() }, now)
    }

    /**
     * Debug builds only: replaces polling with a fixed screen for screenshots.
     * [kind] is discharging, charging, low, alarm, offline or idle; idle shows a real
     * capture from the battery, decoded by [Bms].
     */
    fun demo(kind: String) {
        demo = true
        current.value = Battery("00:00:00:00:00:01", "12V150A00464")
        batteries.value = listOf(current.value!!, Battery("00:00:00:00:00:02", "12V100A00211"))
        val captured = Bms.decode(
            Bms.payload("dd030019053200001a8927100025000000000000000010430304010b77fdd477".hexToByteArray(), Bms.BASIC),
            Bms.payload("dd0400080d330d340d330d33fef777".hexToByteArray(), Bms.CELLS),
        )
        val status = when (kind) {
            "discharging" -> captured.copy(voltage = 13.12, current = -18.4, soc = 64, remainingAh = 64.0, cellsMv = listOf(3281, 3279, 3283, 3280))
            "charging" -> captured.copy(voltage = 13.62, current = 15.3, soc = 82, remainingAh = 82.0, tempsC = listOf(24.1), cellsMv = listOf(3404, 3401, 3407, 3405))
            "low" -> captured.copy(voltage = 12.71, current = -24.5, soc = 14, remainingAh = 14.0, cellsMv = listOf(3178, 3172, 3181, 3176))
            "alarm" -> captured.copy(voltage = 13.31, current = -6.2, soc = 58, remainingAh = 58.0, chargeOn = false, tempsC = listOf(47.5), protections = listOf("charge overtemperature"))
            "offline" -> captured.copy(voltage = 13.12, current = -18.4, soc = 64, remainingAh = 64.0)
            else -> captured
        }
        val problem = if (kind == "offline") "Can't find the battery. It may be out of range, or another phone may be connected to it." else null
        state.value = MonitorState(interpret(status, System.currentTimeMillis() - if (problem != null) 7 * 60_000 else 0), problem)
    }
}
