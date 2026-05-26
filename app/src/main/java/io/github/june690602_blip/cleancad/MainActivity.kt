package io.github.june690602_blip.cleancad

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import io.github.june690602_blip.cleancad.ui.RecentFilesManager
import io.github.june690602_blip.cleancad.ui.SettingsActivity
import io.github.june690602_blip.cleancad.ui.ViewerActivity

class MainActivity : AppCompatActivity() {

    private val openDoc = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { launchViewer(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setSupportActionBar(findViewById<MaterialToolbar>(R.id.toolbar))
        findViewById<Button>(R.id.btn_open).setOnClickListener {
            openDoc.launch(arrayOf("*/*"))
        }
    }

    override fun onResume() {
        super.onResume()
        refreshRecentFiles()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun refreshRecentFiles() {
        val container = findViewById<LinearLayout>(R.id.container_recent)
        val tvEmpty = findViewById<TextView>(R.id.tv_empty)
        container.removeAllViews()

        val files = RecentFilesManager(this).getAll()
        if (files.isEmpty()) {
            tvEmpty.visibility = View.VISIBLE
            return
        }
        tvEmpty.visibility = View.GONE
        files.forEach { file ->
            Button(this).apply {
                text = file.name
                setOnClickListener { launchViewer(Uri.parse(file.uri)) }
                container.addView(this)
            }
        }
    }

    private fun launchViewer(uri: Uri) {
        startActivity(Intent(this, ViewerActivity::class.java).apply { data = uri })
    }
}
