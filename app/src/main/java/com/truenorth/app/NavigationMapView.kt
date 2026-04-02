package com.truenorth.app

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.*

//custom tactical map overlay
class NavigationMapView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val path = mutableListOf<PathPoint>()
    private var telemetry = TelemetryData()
    
    private var scale = 20f
    private var panX = 0f
    private var panY = 0f
    private var autoFit = true

    private val scaleGesture = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            scale = (scale * detector.scaleFactor).coerceIn(0.5f, 500f)
            autoFit = false
            invalidate()
            return true
        }
    })

    private val tapGesture = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean { autoFit = true; invalidate(); return true }
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            panX -= dx; panY -= dy; autoFit = false; invalidate(); return true
        }
    })

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleGesture.onTouchEvent(event)
        tapGesture.onTouchEvent(event)
        return true
    }

    private val bgPaint = Paint().apply { color = Color.parseColor("#070D1A") }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#40FFFFFF"); strokeWidth = 1f; style = Paint.Style.STROKE
    }
    private val gridLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#60FFFFFF"); textSize = 20f; typeface = Typeface.MONOSPACE
    }
    private val axisLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#80FFFFFF"); textSize = 24f; typeface = Typeface.MONOSPACE
    }

    private val pathPaints = mapOf(
        NavigationMode.GPS_LOCK    to Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#00FF88"); strokeWidth = 5f; style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
        },
        NavigationMode.GPS_DEGRADED to Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FFB300"); strokeWidth = 5f; style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
        },
        NavigationMode.TRUENORTH   to Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#00D4FF"); strokeWidth = 6f; style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
        },
        NavigationMode.DEMO        to Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FF6B35"); strokeWidth = 6f; style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
        }
    )

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.FILL
    }
    private val startPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#AAFFCC"); style = Paint.Style.STROKE; strokeWidth = 3f
    }
    private val uncertaintyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4000D4FF"); style = Paint.Style.FILL
    }
    private val uncertaintyBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#9900D4FF"); style = Paint.Style.STROKE; strokeWidth = 1.5f
    }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.FILL_AND_STROKE; strokeWidth = 2f
    }

    private val compassPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#20FFFFFF"); style = Paint.Style.STROKE; strokeWidth = 1.5f
    }
    private val compassNeedlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF4040"); style = Paint.Style.FILL_AND_STROKE; strokeWidth = 2f
    }
    private val compassSPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#AAAAAA"); style = Paint.Style.FILL_AND_STROKE; strokeWidth = 2f
    }
    private val compassTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CCDDEE"); textSize = 22f; textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }

    private val scaleBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CCDDEE"); strokeWidth = 3f; style = Paint.Style.STROKE
    }
    private val scaleTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CCDDEE"); textSize = 24f; textAlign = Paint.Align.CENTER
        typeface = Typeface.MONOSPACE
    }

    private val modeBannerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val modeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 28f; textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }

    private val noDataPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3A6080"); textSize = 36f; textAlign = Paint.Align.CENTER
        typeface = Typeface.MONOSPACE
    }

    private data class PathSegment(val pts: MutableList<PointF>, val mode: NavigationMode)

    private fun buildSegments(): List<PathSegment> {
        val out = mutableListOf<PathSegment>()
        if (path.isEmpty()) return out
        var current = PathSegment(mutableListOf(), path.first().mode)
        out.add(current)
        for (pt in path) {
            if (pt.mode != current.mode) {
                current = PathSegment(mutableListOf(), pt.mode)
                out.add(current)
            }
            current.pts.add(worldToScreen(pt.northM, pt.eastM))
        }
        return out
    }

    fun addPathPoint(pt: PathPoint) {
        path.add(pt)
        if (path.size > 2000) path.removeAt(0)
        invalidate()
    }

    fun updateTelemetry(data: TelemetryData) {
        telemetry = data
        invalidate()
    }

    fun clearPath() {
        path.clear()
        panX = 0f; panY = 0f; scale = 20f
        autoFit = true
        invalidate()
    }

    private fun computeAutoFit() {
        if (!autoFit || path.isEmpty()) return
        val n = path.map { it.northM }; val e = path.map { it.eastM }
        val minN = n.min(); val maxN = n.max(); val minE = e.min(); val maxE = e.max()
        val spanN = max(20.0, maxN - minN); val spanE = max(20.0, maxE - minE)
        val scaleN = (height * 0.7) / spanN; val scaleE = (width * 0.7) / spanE
        scale = minOf(scaleN, scaleE).toFloat().coerceIn(0.5f, 500f)
        panX = (width / 2f - ((minE + maxE) / 2.0 * scale)).toFloat()
        panY = (height / 2f + ((minN + maxN) / 2.0 * scale)).toFloat()
    }

    private fun worldToScreen(northM: Double, eastM: Double): PointF =
        PointF((eastM * scale + panX).toFloat(), (-northM * scale + panY).toFloat())

    override fun onDraw(canvas: Canvas) {
        //transparent overlay logic
        drawCompassRose(canvas)
        drawScaleBar(canvas)
        drawModeBanner(canvas)

        if (path.isEmpty() && telemetry.stepCount == 0) {
            drawIdleMessage(canvas)
        }
    }

    private fun drawGrid(canvas: Canvas) {
        val spacing = pickGridSpacing((100f / scale))
        val startN = floor((-panY / scale) / spacing) * spacing
        val endN   = ceil(( (height - panY) / -scale) / spacing) * spacing
        //...
    }

    private fun pickGridSpacing(ideal: Float): Double {
        val powers = doubleArrayOf(1.0, 2.0, 5.0, 10.0, 20.0, 50.0, 100.0, 200.0, 500.0, 1000.0)
        return powers.minByOrNull { abs(it - ideal) } ?: 10.0
    }

    private fun drawPath(canvas: Canvas) {
        for (seg in buildSegments()) {
            if (seg.pts.size < 2) continue
            val paint = pathPaints[seg.mode] ?: continue
            val graphicPath = Path()
            graphicPath.moveTo(seg.pts.first().x, seg.pts.first().y)
            for (pt in seg.pts.drop(1)) graphicPath.lineTo(pt.x, pt.y)
            canvas.drawPath(graphicPath, paint)
        }
    }

    private fun drawCurrentPosition(canvas: Canvas) {
        val px = worldToScreen(telemetry.northingM, telemetry.eastingM)
        val modeColor = telemetry.mode.color
        val uncertaintyPx = (telemetry.positionUncertaintyM * scale).coerceIn(8.0, 500.0).toFloat()
        uncertaintyPaint.color = Color.argb(60, Color.red(modeColor), Color.green(modeColor), Color.blue(modeColor))
        canvas.drawCircle(px.x, px.y, uncertaintyPx, uncertaintyPaint)
        val headingRad = Math.toRadians(telemetry.headingDeg).toFloat()
        val arrowLen = 36f
        val tipX  = px.x + sin(headingRad) * arrowLen
        val tipY  = px.y - cos(headingRad) * arrowLen
        val leftX = px.x + sin(headingRad - 0.5f) * 16f
        val leftY = px.y - cos(headingRad - 0.5f) * 16f
        val rightX = px.x + sin(headingRad + 0.5f) * 16f
        val rightY = px.y - cos(headingRad + 0.5f) * 16f
        val arrowPath = Path()
        arrowPath.moveTo(tipX, tipY)
        arrowPath.lineTo(leftX, leftY)
        arrowPath.lineTo(rightX, rightY)
        arrowPath.close()
        arrowPaint.color = modeColor
        canvas.drawPath(arrowPath, arrowPaint)
        dotPaint.color = Color.WHITE
        canvas.drawCircle(px.x, px.y, 8f, dotPaint)
    }

    private fun drawCompassRose(canvas: Canvas) {
        val cx = width - 72f
        val cy = 180f 
        val r  = 44f
        canvas.drawCircle(cx, cy, r, compassPaint)
        val heading = Math.toRadians(telemetry.headingDeg).toFloat()
        canvas.drawLine(cx, cy, cx + sin(-heading) * r * 0.85f, cy - cos(-heading) * r * 0.85f, compassNeedlePaint)
    }

    private fun drawScaleBar(canvas: Canvas) {
        val barMetres = pickGridSpacing((100f / scale))
        val barPx = (barMetres * scale).toFloat().coerceIn(40f, 200f)
        val left = 24f; val y = height - 150f
        canvas.drawLine(left, y, left + barPx, y, scaleBarPaint)
        canvas.drawText("${barMetres.toInt()} m", left + barPx / 2f, y - 10f, scaleTextPaint)
    }

    private fun drawModeBanner(canvas: Canvas) {
        val mode = telemetry.mode
        val bannerH = 52f
        val rect = RectF(0f, height - bannerH, width.toFloat(), height.toFloat())
        modeBannerPaint.color = Color.argb(200, 7, 13, 26)
        canvas.drawRect(rect, modeBannerPaint)
        modeTextPaint.color = mode.color
        val modeStr = "● ${mode.displayName}"
        canvas.drawText(modeStr, width / 2f, height - 14f, modeTextPaint)
    }

    private fun drawIdleMessage(canvas: Canvas) {
        val msg = when {
            telemetry.northingM == 0.0 && telemetry.eastingM == 0.0 -> "Waiting for GPS Initial Fix..."
            path.isEmpty() && telemetry.stepCount == 0 -> "Awaiting motion..."
            else -> "Awaiting sensors..."
        }
        canvas.drawText(msg, width / 2f, height / 2f - 20, noDataPaint)
    }
}
