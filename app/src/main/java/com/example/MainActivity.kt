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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.auth.SignageAuthGate
import com.example.service.WatchdogService
import com.example.ui.SignageScreen
import com.example.ui.SignageViewModel
import com.example.ui.orientation.SignageOrientation
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {

    private var activeViewModel: SignageViewModel? = null

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

        setContent {
            MyApplicationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    SignageAuthGate { currentUserId ->
                        val vm: SignageViewModel = viewModel(key = currentUserId)
                        activeViewModel = vm
                        val state by vm.uiState.collectAsStateWithLifecycle()

                        LaunchedEffect(currentUserId) {
                            vm.bindAuthenticatedFirestoreUser(currentUserId)
                        }

                        LaunchedEffect(state.displayOrientation) {
                            SignageOrientation.applyOrientation(
                                this@MainActivity,
                                state.displayOrientation
                            )
                        }

                        SignageScreen(viewModel = vm)
                    }
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
        val vm = activeViewModel ?: return super.onKeyDown(keyCode, event)

        comboResetHandler.removeCallbacks(comboResetRunnable)
        comboResetHandler.postDelayed(comboResetRunnable, 3000L) // 3-second window

        keyComboHistory.add(keyCode)
        if (keyComboHistory.size > 4) {
            keyComboHistory.removeAt(0)
        }

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
            vm.toggleDiagnosticOverlay(true)
            return true
        }

        if (keyCode == KeyEvent.KEYCODE_MENU) {
            vm.onOpenAdminClicked()
            return true
        }

        if (vm.uiState.value.isKioskLocked) {
            when (keyCode) {
                KeyEvent.KEYCODE_HOME,
                KeyEvent.KEYCODE_APP_SWITCH,
                KeyEvent.KEYCODE_SETTINGS -> return true
            }
        }

        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        super.onDestroy()
        comboResetHandler.removeCallbacksAndMessages(null)
    }
}
