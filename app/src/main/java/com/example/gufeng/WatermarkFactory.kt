package com.example.gufeng

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.graphics.ColorUtils
import java.util.Calendar
import java.util.Random
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

enum class Category(val label: String) {
    SEAL("印章"), POEM("诗句"), TERM("节气"), DECO("装饰"), SIGN("落款")
}

/** 一款水印：render(颜色) 返回透明底位图。 */
class WatermarkSpec(
    val id: String,
    val category: Category,
    val defaultColor: Int,
    val render: (Int) -> Bitmap
)

object WatermarkFactory {

    val VERMILION = 0xFFC8312B.toInt()
    val INK = 0xFF1A1A1A.toInt()
    val WHITE = 0xFFFFFFFF.toInt()
    val GOLD = 0xFFE2B746.toInt()
    val INDIGO = 0xFF35506B.toInt()

    val palette: List<Pair<String, Int>> = listOf(
        "朱红" to VERMILION,
        "墨黑" to INK,
        "月白" to WHITE,
        "鎏金" to GOLD,
        "黛青" to INDIGO
    )

    // ---------------------------------------------------------------- 画笔工具

    private fun tp(color: Int, size: Float, bold: Boolean = true) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        textSize = size
        typeface = Typeface.create(Typeface.SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
    }

    private fun clearFill() = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }

    private fun clearStroke(w: Float) = clearFill().apply {
        style = Paint.Style.STROKE
        strokeWidth = w
    }

    private fun clearText(size: Float) = tp(Color.BLACK, size).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }

    private fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }

    private fun stroke(color: Int, w: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = w
        strokeCap = Paint.Cap.ROUND
    }

    private fun drawChar(c: Canvas, s: String, cx: Float, cy: Float, p: Paint) {
        val fm = p.fontMetrics
        c.drawText(s, cx, cy - (fm.ascent + fm.descent) / 2f, p)
    }

    private fun applyShadow(p: Paint, color: Int) {
        if (ColorUtils.calculateLuminance(color) > 0.55) {
            p.setShadowLayer(8f, 2f, 3f, 0xAA000000.toInt())
        } else {
            p.setShadowLayer(6f, 0f, 0f, 0x66FFFFFF)
        }
    }

    /** 给印章加一点“斑驳”的残缺感。 */
    private fun grunge(c: Canvas, w: Int, h: Int, seed: Int, n: Int = 26) {
        val rnd = Random(seed.toLong())
        val p = clearFill()
        repeat(n) {
            c.drawCircle(rnd.nextFloat() * w, rnd.nextFloat() * h, 1f + rnd.nextFloat() * 2.6f, p)
        }
    }

    private fun newBitmap(w: Int, h: Int): Bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)

    // ---------------------------------------------------------------- 印章

    /** 方形印章。filled=true 为白文（实底镂空字），false 为朱文（线框+实字）。 */
    private fun squareSeal(text: String, color: Int, filled: Boolean): Bitmap {
        val chars = text.map { it.toString() }
        val cols = if (chars.size <= 2) 1 else 2
        val rows = ceil(chars.size / cols.toFloat()).toInt()
        val cell = 130f
        val pad = 12f
        val w = (cols * cell + pad * 2).toInt()
        val h = (rows * cell + pad * 2).toInt()
        val bmp = newBitmap(w, h)
        val c = Canvas(bmp)

        val rect = RectF(4f, 4f, w - 4f, h - 4f)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = if (filled) Paint.Style.FILL else Paint.Style.STROKE
            strokeWidth = 9f
        }
        if (!filled) rect.inset(5f, 5f)
        c.drawRoundRect(rect, 10f, 10f, bg)

        val textPaint = if (filled) clearText(cell * 0.8f) else tp(color, cell * 0.8f)
        chars.forEachIndexed { i, ch ->
            val col = if (cols == 1) 0 else (cols - 1) - i / rows
            val row = i % rows
            drawChar(c, ch, pad + col * cell + cell / 2, pad + row * cell + cell / 2, textPaint)
        }
        grunge(c, w, h, text.hashCode())
        return bmp
    }

    /** 圆形印章，2 字左右并排，4 字田字排布。 */
    private fun roundSeal(text: String, color: Int): Bitmap {
        val size = 300
        val bmp = newBitmap(size, size)
        val c = Canvas(bmp)
        val cx = size / 2f
        val cy = size / 2f
        c.drawCircle(cx, cy, size / 2f - 4f, fill(color))
        c.drawCircle(cx, cy, size / 2f - 24f, clearStroke(4f))

        val chars = text.map { it.toString() }
        if (chars.size <= 2) {
            val tpnt = clearText(120f)
            chars.forEachIndexed { i, ch -> drawChar(c, ch, cx + 66f - i * 132f, cy, tpnt) }
        } else {
            val tpnt = clearText(100f)
            val pos = listOf(
                cx + 54f to cy - 54f, cx + 54f to cy + 54f,
                cx - 54f to cy - 54f, cx - 54f to cy + 54f
            )
            chars.take(4).forEachIndexed { i, ch -> drawChar(c, ch, pos[i].first, pos[i].second, tpnt) }
        }
        grunge(c, size, size, text.hashCode(), 30)
        return bmp
    }

    // ---------------------------------------------------------------- 竖排诗句

    private fun verticalPoem(text: String, color: Int): Bitmap {
        val segs = text.split("，", "、").filter { it.isNotBlank() }
        val size = 70f
        val lineH = size * 1.18f
        val colW = size * 1.55f
        val pad = size * 0.6f
        val maxLen = segs.maxOf { it.length }
        val w = (segs.size * colW + pad * 2).toInt()
        val h = (maxLen * lineH + pad * 2).toInt()
        val bmp = newBitmap(w, h)
        val c = Canvas(bmp)
        val p = tp(color, size)
        applyShadow(p, color)
        segs.forEachIndexed { ci, s ->
            val cx = w - pad - colW / 2 - ci * colW
            s.forEachIndexed { ri, ch ->
                drawChar(c, ch.toString(), cx, pad + ri * lineH + lineH / 2, p)
            }
        }
        return bmp
    }

    // ---------------------------------------------------------------- 落款

    private fun cnSmall(n: Int): String {
        val d = arrayOf("", "一", "二", "三", "四", "五", "六", "七", "八", "九", "十")
        return when {
            n <= 10 -> d[n]
            n < 20 -> "十" + d[n - 10]
            n == 20 -> "二十"
            n < 30 -> "廿" + d[n - 20]
            n == 30 -> "三十"
            else -> "三十一"
        }
    }

    private fun dateText(): String {
        val cal = Calendar.getInstance()
        val digits = "〇一二三四五六七八九"
        val year = cal.get(Calendar.YEAR).toString().map { digits[it - '0'] }.joinToString("")
        return year + "年" + cnSmall(cal.get(Calendar.MONTH) + 1) + "月" +
            cnSmall(cal.get(Calendar.DAY_OF_MONTH)) + "日"
    }

    private fun signature(color: Int, withSeal: Boolean): Bitmap {
        val text = dateText()
        val size = 46f
        val lineH = size * 1.2f
        val pad = 16f
        val textW = size * 1.6f
        val textH = text.length * lineH
        val seal = if (withSeal) squareSeal("清欢", VERMILION, true) else null
        val sealW = 70f
        val sealH = if (seal != null) sealW * seal.height / seal.width else 0f
        val w = (max(textW, sealW) + pad * 2).toInt()
        val h = (pad * 2 + textH + (if (seal != null) 14f + sealH else 0f)).toInt()
        val bmp = newBitmap(w, h)
        val c = Canvas(bmp)
        val p = tp(color, size)
        applyShadow(p, color)
        text.forEachIndexed { i, ch ->
            drawChar(c, ch.toString(), w / 2f, pad + i * lineH + lineH / 2, p)
        }
        if (seal != null) {
            val top = pad + textH + 14f
            c.drawBitmap(
                seal, null,
                RectF(w / 2f - sealW / 2, top, w / 2f + sealW / 2, top + sealH),
                Paint(Paint.FILTER_BITMAP_FLAG)
            )
        }
        return bmp
    }

    // ---------------------------------------------------------------- 装饰图案

    private fun blossom(c: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
        val p = fill(color)
        for (i in 0..4) {
            val a = Math.toRadians(i * 72.0 - 90.0)
            c.drawCircle(
                cx + (cos(a) * r * 0.62).toFloat(),
                cy + (sin(a) * r * 0.62).toFloat(),
                r * 0.48f, p
            )
        }
        c.drawCircle(cx, cy, r * 0.3f, clearFill())
        c.drawCircle(cx, cy, r * 0.1f, p)
        val sp = stroke(color, max(1.5f, r * 0.06f))
        for (i in 0..4) {
            val a = Math.toRadians(i * 72.0 - 54.0)
            c.drawLine(
                cx, cy,
                cx + (cos(a) * r * 0.26).toFloat(), cy + (sin(a) * r * 0.26).toFloat(), sp
            )
        }
    }

    private fun plum(color: Int): Bitmap {
        val bmp = newBitmap(340, 340)
        val c = Canvas(bmp)
        val branch = Path().apply {
            moveTo(15f, 320f)
            cubicTo(120f, 270f, 160f, 180f, 300f, 50f)
        }
        c.drawPath(branch, stroke(color, 9f))
        val twig1 = Path().apply { moveTo(135f, 225f); quadTo(170f, 250f, 215f, 240f) }
        val twig2 = Path().apply { moveTo(205f, 150f); quadTo(230f, 185f, 268f, 184f) }
        c.drawPath(twig1, stroke(color, 5f))
        c.drawPath(twig2, stroke(color, 5f))
        blossom(c, 300f, 50f, 30f, color)
        blossom(c, 212f, 140f, 26f, color)
        blossom(c, 140f, 212f, 22f, color)
        blossom(c, 215f, 242f, 18f, color)
        blossom(c, 268f, 184f, 16f, color)
        val p = fill(color)
        c.drawCircle(252f, 100f, 8f, p)
        c.drawCircle(70f, 130f, 9f, p)
        c.drawCircle(110f, 80f, 7f, p)
        c.drawCircle(40f, 70f, 6f, p)
        return bmp
    }

    private fun cloud(color: Int): Bitmap {
        val bmp = newBitmap(420, 250)
        val c = Canvas(bmp)
        val p = fill(color)
        val bumps = listOf(
            Triple(110f, 140f, 60f), Triple(190f, 100f, 70f), Triple(280f, 130f, 60f),
            Triple(345f, 160f, 42f), Triple(60f, 170f, 42f)
        )
        bumps.forEach { (x, y, r) -> c.drawCircle(x, y, r, p) }
        c.drawRoundRect(RectF(40f, 150f, 370f, 205f), 28f, 28f, p)
        val cut = clearStroke(7f)
        bumps.take(3).forEach { (x, y, r) ->
            val k = r * 0.55f
            c.drawArc(RectF(x - k, y - k, x + k, y + k), 200f, 250f, false, cut)
        }
        // 云尾
        val tail = Path().apply { moveTo(70f, 225f); quadTo(130f, 240f, 190f, 225f) }
        c.drawPath(tail, stroke(color, 6f))
        val tail2 = Path().apply { moveTo(230f, 228f); quadTo(290f, 240f, 340f, 225f) }
        c.drawPath(tail2, stroke(color, 6f))
        return bmp
    }

    private fun crescent(color: Int): Bitmap {
        val bmp = newBitmap(300, 300)
        val c = Canvas(bmp)
        c.drawCircle(150f, 150f, 110f, fill(color))
        c.drawCircle(198f, 124f, 95f, clearFill())
        return bmp
    }

    private fun fullMoon(color: Int): Bitmap {
        val bmp = newBitmap(300, 300)
        val c = Canvas(bmp)
        c.drawCircle(150f, 150f, 130f, fill(color).apply { alpha = 0xE6 })
        c.drawCircle(150f, 150f, 112f, clearStroke(4f))
        val cr = clearFill()
        c.drawCircle(110f, 120f, 18f, cr)
        c.drawCircle(188f, 96f, 12f, cr)
        c.drawCircle(192f, 188f, 22f, cr)
        c.drawCircle(112f, 204f, 10f, cr)
        return bmp
    }

    private fun leaf(c: Canvas, x: Float, y: Float, len: Float, angle: Float, p: Paint) {
        c.save()
        c.translate(x, y)
        c.rotate(angle)
        val path = Path().apply {
            moveTo(0f, 0f)
            quadTo(len * 0.5f, -len * 0.2f, len, 0f)
            quadTo(len * 0.5f, len * 0.2f, 0f, 0f)
        }
        c.drawPath(path, p)
        c.restore()
    }

    private fun bamboo(color: Int): Bitmap {
        val bmp = newBitmap(340, 340)
        val c = Canvas(bmp)
        c.drawLine(30f, 310f, 270f, 40f, stroke(color, 8f))
        val p = fill(color)
        for (i in 0..3) {
            val t = 0.3f + 0.2f * i
            val px = 30f + 240f * t
            val py = 310f - 270f * t
            val len = 125f - i * 10f
            leaf(c, px, py, len, 22f, p)
            leaf(c, px, py, len, 158f, p)
            c.drawCircle(px, py, 7f, p)
        }
        leaf(c, 270f, 40f, 100f, -65f, p)
        return bmp
    }

    // ---------------------------------------------------------------- 全部水印

    val all: List<WatermarkSpec> by lazy {
        buildList {
            // 白文方章（实底镂空字）
            val solid2 = listOf("清欢", "浮生", "安然", "知足", "无忧", "墨韵")
            val solid4 = listOf(
                "山河无恙", "岁月静好", "云淡风轻", "花好月圆", "春和景明", "烟火人间",
                "如沐春风", "清风明月", "浮生若梦", "莫失莫忘", "岁岁平安", "一世长安"
            )
            (solid2 + solid4).forEach { t ->
                add(WatermarkSpec("seal_$t", Category.SEAL, VERMILION) { col -> squareSeal(t, col, true) })
            }
            // 朱文方章（线框实字）
            listOf("逍遥", "归去", "且行", "清梦", "山高水长", "不负韶华").forEach { t ->
                add(WatermarkSpec("seal_h_$t", Category.SEAL, VERMILION) { col -> squareSeal(t, col, false) })
            }
            // 圆章
            listOf("风月", "墨香", "随心", "归隐", "如意", "琴棋书画", "梅兰竹菊").forEach { t ->
                add(WatermarkSpec("seal_r_$t", Category.SEAL, VERMILION) { col -> roundSeal(t, col) })
            }

            // 竖排诗句
            val poems = listOf(
                "春风十里不如你",
                "人生若只如初见",
                "落霞与孤鹜齐飞，秋水共长天一色",
                "曾经沧海难为水，除却巫山不是云",
                "大漠孤烟直，长河落日圆",
                "海上生明月，天涯共此时",
                "但愿人长久，千里共婵娟",
                "采菊东篱下，悠然见南山",
                "春江潮水连海平，海上明月共潮生",
                "人间有味是清欢",
                "此心安处是吾乡",
                "行到水穷处，坐看云起时",
                "长风破浪会有时，直挂云帆济沧海",
                "举杯邀明月，对影成三人"
            )
            poems.forEachIndexed { i, t ->
                val col = if (i % 3 == 2) GOLD else WHITE
                add(WatermarkSpec("poem_$i", Category.POEM, col) { c -> verticalPoem(t, c) })
            }

            // 二十四节气
            val terms = listOf(
                "立春", "雨水", "惊蛰", "春分", "清明", "谷雨",
                "立夏", "小满", "芒种", "夏至", "小暑", "大暑",
                "立秋", "处暑", "白露", "秋分", "寒露", "霜降",
                "立冬", "小雪", "大雪", "冬至", "小寒", "大寒"
            )
            val seasonColors = listOf(0xFF6FA86B.toInt(), 0xFFE0645C.toInt(), 0xFFD9902F.toInt(), 0xFF6C93B8.toInt())
            terms.forEachIndexed { i, t ->
                add(WatermarkSpec("term_$t", Category.TERM, seasonColors[i / 6]) { c -> roundSeal(t, c) })
            }

            // 装饰
            add(WatermarkSpec("deco_plum", Category.DECO, 0xFFFFC1CC.toInt()) { c -> plum(c) })
            add(WatermarkSpec("deco_cloud", Category.DECO, WHITE) { c -> cloud(c) })
            add(WatermarkSpec("deco_crescent", Category.DECO, 0xFFFFF3C4.toInt()) { c -> crescent(c) })
            add(WatermarkSpec("deco_moon", Category.DECO, 0xFFFFF3C4.toInt()) { c -> fullMoon(c) })
            add(WatermarkSpec("deco_bamboo", Category.DECO, 0xFF9CCB7A.toInt()) { c -> bamboo(c) })

            // 落款
            add(WatermarkSpec("sign_date", Category.SIGN, WHITE) { c -> signature(c, false) })
            add(WatermarkSpec("sign_date_seal", Category.SIGN, WHITE) { c -> signature(c, true) })
        }
    }
}
