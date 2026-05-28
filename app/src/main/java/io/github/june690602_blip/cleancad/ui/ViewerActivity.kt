package io.github.june690602_blip.cleancad.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.tabs.TabLayout
import io.github.june690602_blip.cleancad.R
import io.github.june690602_blip.cleancad.model.Drawing
import io.github.june690602_blip.cleancad.render.DrawingView
import kotlinx.coroutines.launch

class ViewerActivity : AppCompatActivity() {

    private lateinit var drawingView: DrawingView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvError: TextView
    private lateinit var fabFit: FloatingActionButton
    private lateinit var tabSheets: TabLayout

    private val viewModel: DrawingViewModel by viewModels()

    private var renderedUri: Uri? = null

    private val openDoc = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.load(uri) else finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_viewer)

        drawingView = findViewById(R.id.drawing_view)
        progressBar = findViewById(R.id.progress_bar)
        tvError = findViewById(R.id.tv_error)
        fabFit = findViewById(R.id.fab_fit)
        tabSheets = findViewById(R.id.tab_sheets)
        fabFit.setOnClickListener { drawingView.fitToScreen() }

        tabSheets.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                val sheet = tab.tag as? io.github.june690602_blip.cleancad.model.Sheet
                drawingView.showSheet(sheet)
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {
                val sheet = tab.tag as? io.github.june690602_blip.cleancad.model.Sheet
                drawingView.showSheet(sheet)
            }
        })

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    when (state) {
                        is DrawingState.Idle    -> { /* 초기 상태 */ }
                        is DrawingState.Loading -> showLoading()
                        is DrawingState.Success -> {
                            if (state.uri == renderedUri) return@collect
                            renderedUri = state.uri
                            runCatching {
                                contentResolver.takePersistableUriPermission(
                                    state.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                                )
                            }
                            RecentFilesManager(this@ViewerActivity).add(
                                state.uri.toString(), state.displayName
                            )
                            showDrawing()
                            drawingView.setDrawing(state.drawing)
                            populateSheetsTab(state.drawing)
                        }
                        is DrawingState.Error -> {
                            showError(getString(R.string.error_prefix) + state.message)
                        }
                    }
                }
            }
        }

        if (viewModel.state.value is DrawingState.Idle) {
            val uri = intent.data
            if (uri != null) viewModel.load(uri) else openDoc.launch(arrayOf("*/*"))
        }
    }

    private fun populateSheetsTab(drawing: Drawing) {
        tabSheets.removeAllTabs()
        val sheets = drawing.sheets
        if (sheets.size < 2) {
            tabSheets.visibility = View.GONE
            return
        }

        // "전체" 탭
        tabSheets.addTab(tabSheets.newTab().setText(getString(R.string.sheet_all)).setTag(null))

        sheets.forEachIndexed { idx, sheet ->
            val label = sheet.name ?: getString(R.string.sheet_n, idx + 1)
            tabSheets.addTab(tabSheets.newTab().setText(label).setTag(sheet))
        }

        tabSheets.visibility = View.VISIBLE
        tabSheets.getTabAt(0)?.select()
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

    private fun showLoading() {
        progressBar.visibility = View.VISIBLE
        drawingView.visibility = View.GONE
        tvError.visibility = View.GONE
        fabFit.visibility = View.GONE
        tabSheets.visibility = View.GONE
    }

    private fun showDrawing() {
        progressBar.visibility = View.GONE
        tvError.visibility = View.GONE
        drawingView.visibility = View.VISIBLE
        fabFit.visibility = View.VISIBLE
    }

    private fun showError(msg: String) {
        progressBar.visibility = View.GONE
        drawingView.visibility = View.GONE
        fabFit.visibility = View.GONE
        tvError.visibility = View.VISIBLE
        tvError.text = msg
        tabSheets.visibility = View.GONE
    }
}
