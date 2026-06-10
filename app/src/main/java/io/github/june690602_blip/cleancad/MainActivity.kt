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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import io.github.june690602_blip.cleancad.ui.RecentFilesManager
import io.github.june690602_blip.cleancad.ui.AboutActivity
import io.github.june690602_blip.cleancad.ui.SettingsActivity
import io.github.june690602_blip.cleancad.ui.ViewerActivity
import java.io.File

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
            R.id.action_about -> {
                startActivity(Intent(this, AboutActivity::class.java))
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
        sweepOrphans(files)
        if (files.isEmpty()) {
            tvEmpty.visibility = View.VISIBLE
            return
        }
        tvEmpty.visibility = View.GONE
        files.forEach { file ->
            Button(this).apply {
                text = file.name
                setOnClickListener { launchViewer(file) }
                setOnLongClickListener { confirmDelete(file); true }
                container.addView(this)
            }
        }
    }

    /** 목록에 없는(비정상 종료 등으로 남은) filesDir/recent 복사본을 제거. */
    private fun sweepOrphans(files: List<RecentFilesManager.RecentFile>) {
        val keep = files.mapNotNull { it.localPath }.toSet()
        File(filesDir, "recent").listFiles()?.forEach { f ->
            if (f.absolutePath !in keep) runCatching { f.delete() }
        }
    }

    private fun confirmDelete(file: RecentFilesManager.RecentFile) {
        AlertDialog.Builder(this)
            .setTitle(file.name)
            .setMessage(getString(R.string.recent_delete_confirm))
            .setPositiveButton(getString(R.string.delete)) { _, _ ->
                RecentFilesManager(this).remove(file.uri)
                refreshRecentFiles()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun launchViewer(file: RecentFilesManager.RecentFile) {
        startActivity(Intent(this, ViewerActivity::class.java).apply {
            data = Uri.parse(file.uri)
            putExtra(ViewerActivity.EXTRA_LOCAL_PATH, file.localPath)
            putExtra(ViewerActivity.EXTRA_NAME, file.name)
        })
    }

    private fun launchViewer(uri: Uri) {
        startActivity(Intent(this, ViewerActivity::class.java).apply { data = uri })
    }
}
