package com.sefa.hillclimb

import android.app.Activity
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class MainActivity : Activity() {

    private lateinit var game: GameView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        game = GameView(this)
        setContentView(game)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    override fun onResume() {
        super.onResume()
        game.invalidate()
    }

    override fun onPause() {
        super.onPause()
        game.pauseGame()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!game.onBack()) {
            super.onBackPressed()
        }
    }
}
