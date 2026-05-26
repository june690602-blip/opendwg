package io.github.june690602_blip.cleancad.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.floatingactionbutton.FloatingActionButton
import io.github.june690602_blip.cleancad.NativeDwg
import io.github.june690602_blip.cleancad.R
import io.github.june690602_blip.cleancad.parser.DxfParser
import io.github.june690602_blip.cleancad.render.DrawingView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

class ViewerActivity : AppCompatActivity() {

    private lateinit var drawingView: DrawingView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvError: TextView
    private lateinit var fabFit: FloatingActionButton

    private val openDoc = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) openFile(uri) else finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_viewer)

        drawingView = findViewById(R.id.drawing_view)
        progressBar = findViewById(R.id.progress_bar)
        tvError = findViewById(R.id.tv_error)
        fabFit = findViewById(R.id.fab_fit)

        fabFit.setOnClickListener { drawingView.fitToScreen() }

        val uri = intent.data
        if (uri != null) openFile(uri) else openDoc.launch(arrayOf("*/*"))
    }

    private fun openFile(uri: Uri) {
        showLoading()
        lifecycleScope.launch(Dispatchers.IO) {
            val result = runCatching {
                // 1. content URI → 캐시 파일 (JNI는 실제 파일 경로 필요)
                val dwgFile = File(cacheDir, "current.dwg")
                val stream = contentResolver.openInputStream(uri)
                    ?: throw IOException("파일을 열 수 없습니다: $uri")
                stream.use { it.copyTo(dwgFile.outputStream()) }

                // 2. JNI: DWG → DXF
                val dxfFile = File(cacheDir, "current.dxf")
                val rc = NativeDwg.nativeDwgToDxf(dwgFile.absolutePath, dxfFile.absolutePath)
                if (rc != 0) throw RuntimeException("DWG 변환 실패 (코드: $rc)")

                // 3. DXF 파싱 → Drawing 모델
                val dxfContent = dxfFile.readText()
                DxfParser.parse(dxfContent)
            }

            withContext(Dispatchers.Main) {
                result.fold(
                    onSuccess = { drawing ->
                        // SAF URI 퍼미션 영구 보존 — 없으면 앱 재시작 시 SecurityException
                        runCatching {
                            contentResolver.takePersistableUriPermission(
                                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                            )
                        }
                        val name = uri.lastPathSegment ?: uri.toString()
                        RecentFilesManager(this@ViewerActivity).add(uri.toString(), name)
                        showDrawing()
                        drawingView.setDrawing(drawing)
                    },
                    onFailure = { e ->
                        showError(getString(R.string.error_prefix) + (e.message ?: "알 수 없는 오류"))
                    }
                )
            }
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
