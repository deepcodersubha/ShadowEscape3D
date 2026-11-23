package com.example.shadowescape

import android.os.Bundle
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.filament.utils.Utils
import com.example.shadowescape.engine3d.FilamentGameScene
import com.example.shadowescape.ui3d.DualZoneTouchOverlay

class MainActivity : AppCompatActivity() {

    private lateinit var surfaceView: SurfaceView
    private lateinit var touchOverlay: DualZoneTouchOverlay
    private var gameScene: FilamentGameScene? = null

    private val debugReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            if (intent?.action == "com.example.shadowescape.DODGE") {
                val x = intent.getFloatExtra("x", 0f)
                val y = intent.getFloatExtra("y", 0f)
                touchOverlay.joystickX = x
                touchOverlay.joystickY = y
                touchOverlay.lastActiveJoystickX = x
                touchOverlay.lastActiveJoystickY = y
                touchOverlay.lastActiveJoystickTime = System.currentTimeMillis()
                touchOverlay.onDodgeRoll?.invoke()
            }
        }
    }

    companion object {
        init {
            // Initialize Filament native JNI shared libraries
            Utils.init()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep screen awake while playing
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // True edge-to-edge immersive landscape full screen
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())

        // 3D Viewport & Touch Controls Hierarchy
        surfaceView = SurfaceView(this)
        touchOverlay = DualZoneTouchOverlay(this)

        val root = FrameLayout(this).apply {
            addView(
                surfaceView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
            addView(
                touchOverlay,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }

        setContentView(root)
        gameScene = FilamentGameScene(this, surfaceView, touchOverlay)

        val filter = android.content.IntentFilter("com.example.shadowescape.DODGE")
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(debugReceiver, filter, android.content.Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(debugReceiver, filter)
        }
    }

    override fun onResume() {
        super.onResume()
        gameScene?.start()
    }

    override fun onPause() {
        super.onPause()
        gameScene?.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(debugReceiver)
        } catch (_: Exception) {}
        gameScene?.destroy()
        gameScene = null
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
    }
}
