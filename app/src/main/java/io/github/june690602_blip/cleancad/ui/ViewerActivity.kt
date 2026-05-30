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
import io.github.june690602_blip.cleancad.R
import io.github.june690602_blip.cleancad.render.DrawingView
import kotlinx.coroutines.launch

class ViewerActivity : AppCompatActivity() {

    private lateinit var drawingView: DrawingView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvError: TextView
    private lateinit var fabFit: FloatingActionButton

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
        fabFit.setOnClickListener { drawingView.fitToScreen() }

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
    }
}
