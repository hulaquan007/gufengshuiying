package com.example.gufeng

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 显示照片并叠加水印。水印的位置/缩放/旋转都存在“照片坐标系”里，
 * 所以导出时可以按原图分辨率直接绘制。
 * 手势：单指拖动，双指缩放+旋转。
 */
class EditorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    class Item(val spec: WatermarkSpec, var color: Int, var bitmap: Bitmap) {
        var cx = 0f
        var cy = 0f
        var scale = 1f
        var rotation = 0f
        var alpha = 255
    }

    var base: Bitmap? = null
        private set
    var selected: Item? = null
        private set
    var onSelectionChanged: ((Item?) -> Unit)? = null

    private val items = mutableListOf<Item>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFAAAAAA.toInt()
        textAlign = Paint.Align.CENTER
        textSize = 16f * resources.displayMetrics.density
    }
    private val framePaintDark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0x99000000.toInt()
    }
    private val framePaintLight = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFFFFFFFF.toInt()
    }

    private var viewScale = 1f
    private var offX = 0f
    private var offY = 0f

    private enum class Mode { NONE, DRAG, PINCH }

    private var mode = Mode.NONE
    private var lastX = 0f
    private var lastY = 0f
    private var startDist = 1f
    private var startAngle = 0f
    private var startScale = 1f
    private var startRot = 0f

    // ------------------------------------------------------------ 对外接口

    fun setBase(bmp: Bitmap) {
        base = bmp
        items.clear()
        select(null)
        invalidate()
    }

    fun addItem(spec: WatermarkSpec, color: Int) {
        val b = base ?: return
        val bmp = spec.render(color)
        val item = Item(spec, color, bmp)
        item.cx = b.width / 2f
        item.cy = b.height / 2f
        val target = 0.32f * max(b.width, b.height)
        item.scale = target / max(bmp.width, bmp.height)
        items.add(item)
        select(item)
    }

    fun recolorSelected(color: Int) {
        val item = selected ?: return
        item.color = color
        item.bitmap = item.spec.render(color)
        invalidate()
    }

    fun setSelectedAlpha(a: Int) {
        val item = selected ?: return
        item.alpha = a.coerceIn(10, 255)
        invalidate()
    }

    fun deleteSelected() {
        val item = selected ?: return
        items.remove(item)
        select(null)
    }

    fun clearItems() {
        items.clear()
        select(null)
    }

    /** 以原图分辨率合成一张新图。 */
    fun exportBitmap(): Bitmap? {
        val b = base ?: return null
        val out = b.copy(Bitmap.Config.ARGB_8888, true)
        val c = Canvas(out)
        items.forEach { drawItem(c, it) }
        return out
    }

    // ------------------------------------------------------------ 绘制

    private fun layoutBase() {
        val b = base ?: return
        viewScale = min(width / b.width.toFloat(), height / b.height.toFloat())
        offX = (width - b.width * viewScale) / 2f
        offY = (height - b.height * viewScale) / 2f
    }

    private fun drawItem(c: Canvas, item: Item) {
        c.save()
        c.translate(item.cx, item.cy)
        c.rotate(item.rotation)
        c.scale(item.scale, item.scale)
        paint.alpha = item.alpha
        c.drawBitmap(item.bitmap, -item.bitmap.width / 2f, -item.bitmap.height / 2f, paint)
        paint.alpha = 255
        c.restore()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(0xFF1E1E1E.toInt())
        val b = base
        if (b == null) {
            canvas.drawText("点上方「选择照片」开始", width / 2f, height / 2f, hintPaint)
            return
        }
        layoutBase()
        canvas.save()
        canvas.translate(offX, offY)
        canvas.scale(viewScale, viewScale)
        canvas.drawBitmap(b, 0f, 0f, paint)
        items.forEach { drawItem(canvas, it) }

        selected?.let { s ->
            canvas.save()
            canvas.translate(s.cx, s.cy)
            canvas.rotate(s.rotation)
            canvas.scale(s.scale, s.scale)
            val k = s.scale * viewScale
            framePaintDark.strokeWidth = 5f / k
            framePaintLight.strokeWidth = 2.5f / k
            framePaintLight.pathEffect = DashPathEffect(floatArrayOf(14f / k, 10f / k), 0f)
            val hw = s.bitmap.width / 2f
            val hh = s.bitmap.height / 2f
            canvas.drawRect(-hw, -hh, hw, hh, framePaintDark)
            canvas.drawRect(-hw, -hh, hw, hh, framePaintLight)
            canvas.restore()
        }
        canvas.restore()
    }

    // ------------------------------------------------------------ 触摸

    private fun select(item: Item?) {
        if (selected !== item) {
            selected = item
            onSelectionChanged?.invoke(item)
        }
        if (item != null) {
            items.remove(item)
            items.add(item) // 提到最上层
        }
        invalidate()
    }

    private fun hitTest(px: Float, py: Float): Item? {
        for (i in items.indices.reversed()) {
            val it = items[i]
            val dx = px - it.cx
            val dy = py - it.cy
            val r = Math.toRadians(-it.rotation.toDouble())
            val lx = (dx * cos(r) - dy * sin(r)).toFloat() / it.scale
            val ly = (dx * sin(r) + dy * cos(r)).toFloat() / it.scale
            val margin = 20f / (it.scale * viewScale)
            if (abs(lx) <= it.bitmap.width / 2f + margin && abs(ly) <= it.bitmap.height / 2f + margin) {
                return it
            }
        }
        return null
    }

    private fun dist(e: MotionEvent) = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))

    private fun angle(e: MotionEvent) =
        Math.toDegrees(atan2((e.getY(1) - e.getY(0)).toDouble(), (e.getX(1) - e.getX(0)).toDouble())).toFloat()

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (base == null) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val px = (e.x - offX) / viewScale
                val py = (e.y - offY) / viewScale
                val hit = hitTest(px, py)
                select(hit)
                lastX = e.x
                lastY = e.y
                mode = if (hit != null) Mode.DRAG else Mode.NONE
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                val s = selected
                if (s != null && e.pointerCount == 2) {
                    mode = Mode.PINCH
                    startDist = max(dist(e), 1f)
                    startAngle = angle(e)
                    startScale = s.scale
                    startRot = s.rotation
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val s = selected
                if (s != null) {
                    if (mode == Mode.DRAG && e.pointerCount == 1) {
                        s.cx += (e.x - lastX) / viewScale
                        s.cy += (e.y - lastY) / viewScale
                        lastX = e.x
                        lastY = e.y
                        invalidate()
                    } else if (mode == Mode.PINCH && e.pointerCount >= 2) {
                        s.scale = (startScale * dist(e) / startDist).coerceIn(0.02f, 20f)
                        s.rotation = startRot + (angle(e) - startAngle)
                        invalidate()
                    }
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (selected != null && e.pointerCount == 2) {
                    val idx = if (e.actionIndex == 0) 1 else 0
                    lastX = e.getX(idx)
                    lastY = e.getY(idx)
                    mode = Mode.DRAG
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> mode = Mode.NONE
        }
        return true
    }
}
