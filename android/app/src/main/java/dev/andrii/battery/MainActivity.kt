package dev.andrii.battery

import android.Manifest
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.update

private const val WATCH_KEY = "watch"
private val BLUETOOTH = arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)

class MainActivity : ComponentActivity() {
    private val prefs by lazy { getSharedPreferences("battery", MODE_PRIVATE) }
    private var watching by mutableStateOf(false)

    private val askBluetooth = registerForActivityResult(RequestMultiplePermissions()) { start() }
    // Watching is useful even without notifications, so it turns on whichever way this goes.
    private val askNotifications = registerForActivityResult(RequestPermission()) { changeWatching(true) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        watching = prefs.getBoolean(WATCH_KEY, false)
        Monitor.load(this)
        // Debug builds: `adb shell am start -n dev.andrii.battery/.MainActivity --es demo low`
        val demo = intent.getStringExtra("demo")
        if (demo != null && applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) Monitor.demo(demo)

        setContent {
            val state by Monitor.state.collectAsStateWithLifecycle()
            val current by Monitor.current.collectAsStateWithLifecycle()
            val batteries by Monitor.batteries.collectAsStateWithLifecycle()
            BatteryScreen(
                state, current, batteries, watching,
                onWatchingChange = { on ->
                    if (on && Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
                        askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        changeWatching(on)
                    }
                },
                onConnect = { Monitor.connect(this, it) },
                onForget = { Monitor.forget(this, it) },
                onRefresh = { Monitor.refresh(this) },
            )
        }
    }

    override fun onStart() {
        super.onStart()
        Monitor.uiVisible.value = true
        if (BLUETOOTH.all(::granted)) start() else askBluetooth.launch(BLUETOOTH)
    }

    override fun onStop() {
        Monitor.uiVisible.value = false
        super.onStop()
    }

    private fun start() {
        if (!BLUETOOTH.all(::granted)) {
            Monitor.state.update { it.copy(problem = "Allow Nearby devices for this app in Settings, so it can reach the battery.") }
            return
        }
        Monitor.ensureRunning(this)
        if (watching) changeWatching(true) // the service doesn't survive a reboot; bring it back
    }

    private fun changeWatching(on: Boolean) {
        // A connectedDevice foreground service may only start with Bluetooth granted.
        if (on && !BLUETOOTH.all(::granted)) return askBluetooth.launch(BLUETOOTH)
        watching = on
        prefs.edit { putBoolean(WATCH_KEY, on) }
        val service = Intent(this, WatchService::class.java)
        if (on) startForegroundService(service) else stopService(service)
    }

    private fun granted(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
}
