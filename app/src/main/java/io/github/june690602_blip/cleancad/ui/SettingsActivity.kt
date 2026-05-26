package io.github.june690602_blip.cleancad.ui

import android.content.Context
import android.os.Bundle
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.appbar.MaterialToolbar
import io.github.june690602_blip.cleancad.BuildConfig
import io.github.june690602_blip.cleancad.R

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        findViewById<TextView>(R.id.tv_version).text =
            getString(R.string.settings_version, BuildConfig.VERSION_NAME)

        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val rgTheme = findViewById<RadioGroup>(R.id.rg_theme)

        // Restore saved selection before attaching the listener to avoid a spurious recreate().
        val savedMode = prefs.getString("night_mode", "system") ?: "system"
        when (savedMode) {
            "light" -> findViewById<RadioButton>(R.id.rb_light).isChecked = true
            "dark"  -> findViewById<RadioButton>(R.id.rb_dark).isChecked = true
            else    -> findViewById<RadioButton>(R.id.rb_system).isChecked = true
        }

        rgTheme.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.rb_light -> "light"
                R.id.rb_dark  -> "dark"
                else          -> "system"
            }
            // Skip no-op: user tapped the already-selected option.
            if (mode == prefs.getString("night_mode", "system")) return@setOnCheckedChangeListener
            prefs.edit().putString("night_mode", mode).apply()
            AppCompatDelegate.setDefaultNightMode(
                when (mode) {
                    "light" -> AppCompatDelegate.MODE_NIGHT_NO
                    "dark"  -> AppCompatDelegate.MODE_NIGHT_YES
                    else    -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                }
            )
            recreate()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
