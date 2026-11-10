package dev.andrii.battery

import android.text.format.DateUtils
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch

private val Background = Color(0xFF000000)
private val Card = Color(0xFF111113)
private val Raised = Color(0xFF18181B)
private val Menu = Color(0xFF232326)
private val Track = Color(0xFF1C1C1E)
private val Line = Color(0xFF1C1C1E)
private val Hairline = Color(0xFF141416)
private val Border = Color(0xFF27272A)
private val Faint = Color(0xFF3F3F46)
private val Ink = Color(0xFFF4F4F5)
private val Soft = Color(0xFFA1A1AA)
private val Mute = Color(0xFF71717A)
private val Lime = Color(0xFFC6F432)
private val Red = Color(0xFFF2555A)
private val Chosen = Color(0xFF1A1D12)
private val ChosenBorder = Color(0xFF3B4A10)

private fun inter(weight: Int, opticalSize: Float) = Font(
    R.font.inter,
    FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight), FontVariation.Setting("opsz", opticalSize)),
)

// Inter's optical size axis: 14 is drawn for running text, 32 tightens the large figures.
private val TextFont = FontFamily(inter(400, 14f), inter(500, 14f), inter(600, 14f), inter(700, 14f))
private val DisplayFont = FontFamily(inter(700, 32f), inter(800, 32f))

private fun body(size: TextUnit, weight: Int = 400, tracking: TextUnit = 0.sp) = TextStyle(
    fontFamily = TextFont,
    fontWeight = FontWeight(weight),
    fontSize = size,
    letterSpacing = tracking,
    fontFeatureSettings = "tnum",
)

private fun display(size: TextUnit, weight: Int, tracking: TextUnit) = TextStyle(
    fontFamily = DisplayFont,
    fontWeight = FontWeight(weight),
    fontSize = size,
    lineHeight = size * 1.05,
    letterSpacing = tracking,
    fontFeatureSettings = "tnum",
)

@Composable
fun BatteryScreen(
    state: MonitorState,
    current: Battery?,
    batteries: List<Battery>,
    watching: Boolean,
    onWatchingChange: (Boolean) -> Unit,
    onConnect: (Battery) -> Unit,
    onForget: (Battery) -> Unit,
    onRefresh: () -> Unit,
) {
    val reading = state.reading
    val low = reading != null && reading.status.soc <= 20 && reading.mode != Mode.Charging
    val accent by animateColorAsState(if (low) Red else Lime, label = "accent")
    var picking by rememberSaveable { mutableStateOf(false) }
    // With no battery chosen, such as on first launch, there is nothing else to do.
    LaunchedEffect(current) { if (current == null) picking = true }

    Column(
        Modifier
            .fillMaxSize()
            .background(Background)
            .verticalScroll(rememberScrollState())
            .safeDrawingPadding()
            .padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        Header(state, current, accent, onPick = { picking = true }, onRefresh)
        when {
            current == null -> NoBattery(onPick = { picking = true })
            reading == null -> Searching(state.problem)
            else -> {
                Banners(state, low)
                Details(reading, accent)
            }
        }
        Spacer(Modifier.height(24.dp))
        WatchSwitch(watching, accent, onWatchingChange)
    }

    if (picking) {
        BatterySheet(
            state, current, batteries,
            onConnect = {
                picking = false
                onConnect(it)
            },
            onForget = onForget,
            onDismiss = { picking = false },
        )
    }
}

@Composable
private fun Header(state: MonitorState, current: Battery?, accent: Color, onPick: () -> Unit, onRefresh: () -> Unit) {
    val live = state.reading != null && state.problem == null
    val busy = state.problem == SEARCHING
    Row(verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier
                .weight(1f)
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClickLabel = "Choose a battery", onClick = onPick)
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(8.dp).background(if (live) accent else Faint, CircleShape))
            Spacer(Modifier.width(10.dp))
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(current?.name ?: "Choose a battery", style = body(16.sp, 600), color = Ink)
                    Spacer(Modifier.width(4.dp))
                    Icon(painterResource(R.drawable.ic_chevron_down), null, Modifier.size(16.dp), tint = Mute)
                }
                if (current != null) {
                    Text(
                        when {
                            live -> "Live"
                            busy -> "Connecting…"
                            else -> "Offline"
                        },
                        style = body(12.sp),
                        color = Mute,
                    )
                }
            }
        }
        // Disabled while a search is already under way: Android stops answering scans started too often.
        if (current != null) {
            IconButton(onClick = onRefresh, enabled = !busy, modifier = Modifier.border(1.dp, Border, CircleShape)) {
                val spin = if (busy) {
                    val turn by rememberInfiniteTransition(label = "refresh")
                        .animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "turn")
                    Modifier.graphicsLayer { rotationZ = turn }
                } else {
                    Modifier
                }
                Icon(painterResource(R.drawable.ic_refresh), "Reconnect", Modifier.size(18.dp).then(spin), tint = Soft)
            }
        }
    }
}

@Composable
private fun Banners(state: MonitorState, low: Boolean) {
    val reading = state.reading ?: return
    val status = reading.status
    val at = DateUtils.formatDateTime(LocalContext.current, reading.at, DateUtils.FORMAT_SHOW_TIME)
    Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.problem != null) Banner(state.problem, "Showing the last reading, from $at.", alarm = false)
        if (low) Banner("Battery low", "Turn off what you can spare.", alarm = true)
        if (status.protections.isNotEmpty()) {
            Banner("Protection active", status.protections.joinToString().replaceFirstChar { it.uppercase() }, alarm = true)
        } else {
            if (!status.chargeOn) Banner("Charging is switched off", "The battery won't take charge right now.", alarm = false)
            if (!status.dischargeOn) Banner("Output is switched off", "The battery isn't powering anything right now.", alarm = false)
        }
    }
}

@Composable
private fun Banner(title: String, text: String, alarm: Boolean) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(if (alarm) Red else Card, RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(title, style = body(15.sp, 600), color = if (alarm) Background else Ink)
        Text(text, style = body(13.sp), color = if (alarm) Background.copy(alpha = 0.75f) else Mute)
    }
}

@Composable
private fun NoBattery(onPick: () -> Unit) {
    Spacer(Modifier.height(40.dp))
    Text("No battery chosen", style = display(46.sp, 800, (-1.5).sp), color = Ink)
    Spacer(Modifier.height(10.dp))
    Text("Pick one of yours or a battery nearby. The app remembers it and connects to it from then on.", style = body(15.sp), color = Mute)
    Spacer(Modifier.height(20.dp))
    Button(onClick = onPick, colors = ButtonDefaults.buttonColors(containerColor = Lime, contentColor = Background)) {
        Text("Choose a battery", style = body(15.sp, 600))
    }
}

@Composable
private fun Searching(problem: String?) {
    Spacer(Modifier.height(40.dp))
    Text("Looking for the battery", style = display(46.sp, 800, (-1.5).sp), color = Ink)
    Spacer(Modifier.height(10.dp))
    Text(
        if (problem == null || problem == SEARCHING) "Keep the phone within a few metres of it." else problem,
        style = body(15.sp),
        color = Mute,
    )
}

@Composable
private fun Details(reading: Reading, accent: Color) {
    val status = reading.status
    val (big, sub) = when {
        reading.minutes == null -> "${status.soc}%" to flowText(reading)
        reading.mode == Mode.Charging -> formatMinutes(reading.minutes) to "to full at ${reading.watts} W"
        else -> formatMinutes(reading.minutes) to "left at ${reading.watts} W"
    }
    Spacer(Modifier.height(28.dp))
    Text(big, style = display(58.sp, 800, (-2).sp), color = Ink)
    Spacer(Modifier.height(6.dp))
    Text(sub, style = body(17.sp, 500), color = Mute)
    Spacer(Modifier.height(26.dp))
    Row {
        // Without an estimate the charge is already the big figure above.
        if (reading.minutes == null) {
            Text("Charge", Modifier.alignByBaseline(), style = body(13.sp), color = Mute)
        } else {
            Text("${status.soc}%", Modifier.alignByBaseline(), style = display(28.sp, 700, (-1).sp), color = accent)
        }
        Spacer(Modifier.weight(1f))
        Text(
            "%.0f of %.0f Ah".format(status.remainingAh, status.capacityAh),
            Modifier.alignByBaseline(),
            style = body(13.sp),
            color = Mute,
        )
    }
    Spacer(Modifier.height(8.dp))
    ChargeBar(status.soc, accent)
    Spacer(Modifier.height(22.dp))
    HorizontalDivider(color = Line)
    Row(Modifier.height(IntrinsicSize.Min).padding(vertical = 14.dp)) {
        val power = when (reading.mode) {
            Mode.Charging -> "Power in"
            Mode.Discharging -> "Power out"
            Mode.Idle -> "Power"
        }
        Figure("${reading.watts} W", power, Modifier.weight(1f))
        VerticalDivider(color = Line)
        Figure("%.1f A".format(abs(status.current)), "Current", Modifier.weight(1f).padding(start = 12.dp))
        VerticalDivider(color = Line)
        Figure("%.2f V".format(status.voltage), "Voltage", Modifier.weight(1f).padding(start = 12.dp))
    }
    HorizontalDivider(color = Line)
    Column(Modifier.padding(vertical = 14.dp)) {
        Row {
            Text("Cells", style = body(15.sp), color = Mute)
            Spacer(Modifier.weight(1f))
            if (status.cellsMv.isNotEmpty()) {
                Text("within ${status.cellsMv.max() - status.cellsMv.min()} mV", style = body(15.sp), color = Ink)
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            status.cellsMv.forEach {
                Text("%.3f V".format(it / 1000.0), style = body(14.sp), color = Mute)
            }
        }
    }
    HorizontalDivider(color = Hairline)
    DetailRow("Temperature", status.tempsC.joinToString(" / ") { "%.0f".format(it) } + " °C")
    HorizontalDivider(color = Hairline)
    DetailRow("Charge cycles", "${status.cycles}")
}

@Composable
private fun ChargeBar(soc: Int, accent: Color) {
    val fraction by animateFloatAsState(soc.coerceIn(0, 100) / 100f, label = "charge")
    Box(Modifier.fillMaxWidth().height(10.dp).background(Track, RoundedCornerShape(5.dp))) {
        Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(accent, RoundedCornerShape(5.dp)))
    }
}

@Composable
private fun Figure(value: String, label: String, modifier: Modifier) {
    Column(modifier) {
        Text(value, style = body(18.sp, 700, (-0.5).sp), color = Ink, maxLines = 1)
        Spacer(Modifier.height(2.dp))
        Text(label, style = body(12.sp), color = Mute)
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 14.dp)) {
        Text(label, style = body(15.sp), color = Mute)
        Spacer(Modifier.weight(1f))
        Text(value, style = body(15.sp), color = Ink)
    }
}

@Composable
private fun WatchSwitch(watching: Boolean, accent: Color, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Card)
            .toggleable(value = watching, onValueChange = onChange, role = Role.Switch)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Alert me when low", style = body(15.sp, 600), color = Ink)
            Spacer(Modifier.height(2.dp))
            Text("Keeps checking in the background and alerts at 20% and 10%.", style = body(12.sp), color = Mute)
        }
        Spacer(Modifier.width(14.dp))
        Switch(
            checked = watching,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedTrackColor = accent,
                checkedThumbColor = Background,
                uncheckedTrackColor = Raised,
                uncheckedBorderColor = Border,
                uncheckedThumbColor = Mute,
            ),
        )
    }
}

/**
 * Lists the added batteries and the others in range, scanning while it is open.
 * Tapping one connects to it; an added one can be forgotten from its menu.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BatterySheet(
    state: MonitorState,
    current: Battery?,
    batteries: List<Battery>,
    onConnect: (Battery) -> Unit,
    onForget: (Battery) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    // shortcut: a battery that leaves range stays listed until the sheet reopens; drop entries not heard for a while if that confuses.
    val heard = remember { mutableStateMapOf<String, Nearby>() }
    LaunchedEffect(Unit) {
        while (true) {
            BmsConnection.nearby(context).catch {}.collect { heard[it.address] = it }
            delay(5_000) // Bluetooth off or not allowed yet: try again once it may be
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Card,
        contentColor = Ink,
        scrimColor = Background.copy(alpha = 0.62f),
        dragHandle = { BottomSheetDefaults.DragHandle(color = Faint) },
    ) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 20.dp),
        ) {
            Text("Batteries", style = body(19.sp, 700, (-0.3).sp), color = Ink)
            Spacer(Modifier.height(4.dp))
            Text(
                "A battery talks to one phone at a time, so one connected to another phone won't show up.",
                style = body(13.sp),
                color = Mute,
            )
            if (batteries.isNotEmpty()) {
                SheetSection("Yours")
                batteries.forEach { battery ->
                    val chosen = battery == current
                    val status = when {
                        chosen && state.problem == null -> "Connected · ${state.reading?.status?.soc}%"
                        chosen && state.problem == SEARCHING -> "Connecting…"
                        chosen -> "Can't reach it right now"
                        battery.address in heard -> "In range · tap to switch"
                        else -> "Not in range"
                    }
                    SheetRow(battery.name, status, chosen, onClick = { if (chosen) onDismiss() else onConnect(battery) }) {
                        ForgetMenu { onForget(battery) }
                    }
                }
            }
            val others = heard.values.filter { nearby -> batteries.none { it.address == nearby.address } }.sortedByDescending { it.rssi }
            if (others.isNotEmpty()) {
                SheetSection("Nearby")
                others.forEach { nearby ->
                    SheetRow(
                        nearby.name,
                        if (nearby.rssi < -80) "Weak signal" else "In range",
                        chosen = false,
                        onClick = { onConnect(Battery(nearby.address, nearby.name)) },
                    ) {
                        SignalBars(nearby.rssi)
                        Text("Add", Modifier.padding(end = 10.dp), style = body(13.sp, 600), color = Lime)
                    }
                }
            }
            Row(Modifier.padding(top = 10.dp, start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(14.dp), color = Lime, strokeWidth = 2.dp, trackColor = Faint)
                Spacer(Modifier.width(10.dp))
                Text("Looking for more…", style = body(13.sp), color = Mute)
            }
        }
    }
}

@Composable
private fun SheetSection(title: String) {
    Text(
        title.uppercase(),
        Modifier.padding(start = 2.dp, top = 18.dp, bottom = 8.dp),
        style = body(11.sp, 600, 1.2.sp),
        color = Mute,
    )
}

@Composable
private fun SheetRow(name: String, status: String, chosen: Boolean, onClick: () -> Unit, trailing: @Composable RowScope.() -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier
            .padding(bottom = 8.dp)
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .border(1.dp, if (chosen) ChosenBorder else Color.Transparent, shape)
            .clip(shape)
            .background(if (chosen) Chosen else Raised)
            .clickable(onClick = onClick)
            .padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, style = body(15.sp, 600), color = Ink)
            Spacer(Modifier.height(2.dp))
            Text(status, style = body(12.sp), color = if (chosen) Lime else Mute)
        }
        trailing()
    }
}

@Composable
private fun ForgetMenu(onForget: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(painterResource(R.drawable.ic_more), "Battery options", Modifier.size(18.dp), tint = Soft)
        }
        DropdownMenu(open, onDismissRequest = { open = false }, shape = RoundedCornerShape(14.dp), containerColor = Menu) {
            DropdownMenuItem(
                text = { Text("Forget battery", style = body(15.sp)) },
                onClick = {
                    open = false
                    onForget()
                },
                colors = MenuDefaults.itemColors(textColor = Red),
            )
        }
    }
}

@Composable
private fun SignalBars(rssi: Int) {
    val lit = when {
        rssi >= -60 -> 4
        rssi >= -70 -> 3
        rssi >= -80 -> 2
        else -> 1
    }
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
        listOf(4, 7, 10, 12).forEachIndexed { i, height ->
            Box(Modifier.size(3.dp, height.dp).background(if (i < lit) Soft else Faint, RoundedCornerShape(1.dp)))
        }
    }
}
