package io.github.june690602_blip.cleancad.render

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import io.github.june690602_blip.cleancad.model.Drawing

class DrawingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var drawing: Drawing? = null
    private var matrix = Matrix()
    private val renderer = EntityRenderer()

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val s = detector.scaleFactor.coerceIn(0.5f, 2.0f)
                matrix.postScale(s, s, detector.focusX, detector.focusY)
                invalidate()
                return true
            }
        }
    )

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                matrix.postTranslate(-distanceX, -distanceY)
                invalidate()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                fitToScreen()
                return true
            }
        }
    )

    /** 외부에서 Drawing 모델을 설정한다. 메인 스레드에서 호출해야 한다. */
    fun setDrawing(drawing: Drawing) {
        this.drawing = drawing
        if (width > 0 && height > 0) fitToScreen() else matrix = Matrix()
        invalidate()
    }

    /** 화면에 도면 전체가 들어오도록 Matrix를 초기화한다. */
    fun fitToScreen() {
        val box = drawing?.extents ?: return
        if (width <= 0 || height <= 0) return
        matrix = CoordTransform.fitMatrix(box, width, height)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (drawing != null) fitToScreen()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        return true
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.WHITE)
        val d = drawing ?: return
        renderer.drawAll(d.entities, canvas, matrix)
    }
}
