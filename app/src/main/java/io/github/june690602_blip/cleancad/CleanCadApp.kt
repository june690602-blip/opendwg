package io.github.june690602_blip.cleancad

import android.app.Application
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

class CleanCadApp : Application() {

    override fun onCreate() {
        super.onCreate()
        applyTheme(this)
    }

    companion object {
        fun applyTheme(context: Context) {
            val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            AppCompatDelegate.setDefaultNightMode(
                when (prefs.getString("night_mode", "system")) {
                    "light" -> AppCompatDelegate.MODE_NIGHT_NO
                    "dark"  -> AppCompatDelegate.MODE_NIGHT_YES
                    else    -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                }
            )
        }
    }
}
