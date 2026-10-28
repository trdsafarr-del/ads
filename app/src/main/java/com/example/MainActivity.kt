package com.example

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import com.example.service.WatchdogService
import com.example.ui.SignageScreen
import com.example.ui.SignageViewModel
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {

    private val viewModel: SignageViewModel by viewModels()

    // Diagnostic key combo tracking: MENU -> UP -> DOWN -> CENTER
    private val keyComboHistory = mutableListOf<Int>()
    private val comboResetHandler = Handler(Looper.getMainLooper())
    private val comboResetRunnable = Runnable { keyComboHistory.clear() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 1. Keep TV screen on continuously (24/7 digital signage operation)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 2. Hide all system UI (immersive sticky kiosk mode)
        hideSystemUI()

        // 3. Start 24/7 Watchdog foreground monitor for crash recovery
        WatchdogService.start(this)

        // 4. Dynamically apply Display Orientation (Landscape, Portrait totem, etc.)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    com.example.ui.orientation.SignageOrientation.applyOrientation(
                        this@MainActivity,
                        state.displayOrientation
                    )
                }
            }
        }

        setContent {
            MyApplicationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    SignageScreen(viewModel = viewModel)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemUI()
        WatchdogService.ping(this)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemUI()
            WatchdogService.ping(this)
        }
    }

    /**
     * Enforces Immersive Sticky Full-Screen mode for digital signage kiosk.
     */
    private fun hideSystemUI() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(
            WindowInsetsCompat.Type.statusBars() or
            WindowInsetsCompat.Type.navigationBars() or
            WindowInsetsCompat.Type.systemGestures()
        )
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    /**
     * TV Remote Control Key Handling (Kiosk Lockdown & Hidden Diagnostic Combo).
     * Sequence: MENU -> DPAD_UP -> DPAD_DOWN -> DPAD_CENTER / ENTER
     * triggers the hidden Diagnostic / Admin Overlay.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        WatchdogService.ping(this)

        comboResetHandler.removeCallbacks(comboResetRunnable)
        comboResetHandler.postDelayed(comboResetRunnable, 3000L) // 3-second window

        keyComboHistory.add(keyCode)
        if (keyComboHistory.size > 4) {
            keyComboHistory.removeAt(0)
        }

        // Check if matching hidden diagnostic sequence: MENU + UP + DOWN + OK
        val expectedCombo = listOf(
            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_CENTER
        )
        val expectedComboEnter = listOf(
            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_ENTER
        )

        if (keyComboHistory == expectedCombo || keyComboHistory == expectedComboEnter) {
            Log.i("MainActivity", "Secret diagnostic key combination triggered!")
            keyComboHistory.clear()
            viewModel.toggleDiagnosticOverlay(true)
            return true
        }

        // Quick MENU key opens Admin authentication dialog
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            viewModel.onOpenAdminClicked()
            return true
        }

        // If Kiosk Mode is locked, block non-essential keys to prevent unauthorized tampering
        if (viewModel.uiState.value.isKioskLocked) {
            when (keyCode) {
                KeyEvent.KEYCODE_HOME,
                KeyEvent.KEYCODE_APP_SWITCH,
                KeyEvent.KEYCODE_SETTINGS -> return true // Blocked in kiosk
            }
        }

        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        super.onDestroy()
        comboResetHandler.removeCallbacksAndMessages(null)
    }
}
