package com.example.gufeng

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.exifinterface.media.ExifInterface
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

class MainActivity : AppCompatActivity() {

    private lateinit var editor: EditorView
    private lateinit var tabs: LinearLayout
    private lateinit var strip: LinearLayout
    private lateinit var alphaBar: SeekBar

    private var category = Category.SEAL
    private val thumbs = ConcurrentHashMap<String, Bitmap>()

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) loadPhoto(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        editor = findViewById(R.id.editor)
        tabs = findViewById(R.id.tabs)
        strip = findViewById(R.id.strip)
        alphaBar = findViewById(R.id.alphaBar)

        findViewById<Button>(R.id.btnPick).setOnClickListener { pickImage.launch("image/*") }
        findViewById<Button>(R.id.btnSave).setOnClickListener { save() }
        findViewById<Button>(R.id.btnDelete).setOnClickListener {
            if (editor.selected == null) toast("请先点选一个水印") else editor.deleteSelected()
        }
        findViewById<Button>(R.id.btnClear).setOnClickListener { editor.clearItems() }

        alphaBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) editor.setSelectedAlpha(progress * 255 / 100)
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        editor.onSelectionChanged = { item ->
            if (item != null) alphaBar.progress = item.alpha * 100 / 255
        }

        buildPalette()
        buildTabs()
        showCategory(Category.SEAL)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    // ------------------------------------------------------------ UI 构建

    private fun buildPalette() {
        val row = findViewById<LinearLayout>(R.id.colorRow)
        WatermarkFactory.palette.forEach { (_, color) ->
            val v = View(this)
            v.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
                setStroke(dp(2), 0xFFAAAAAA.toInt())
            }
            v.layoutParams = LinearLayout.LayoutParams(dp(30), dp(30)).apply { rightMargin = dp(8) }
            v.setOnClickListener {
                if (editor.selected == null) toast("先点选一个水印，再换颜色")
                else editor.recolorSelected(color)
            }
            row.addView(v)
        }
    }

    private fun buildTabs() {
        Category.values().forEach { c ->
            val tv = TextView(this).apply {
                text = c.label
                textSize = 15f
                setTextColor(Color.WHITE)
                setPadding(dp(16), dp(8), dp(16), dp(8))
                tag = c
                setOnClickListener { showCategory(c) }
            }
            tabs.addView(tv)
        }
    }

    private fun highlightTabs() {
        for (i in 0 until tabs.childCount) {
            val tv = tabs.getChildAt(i)
            tv.setBackgroundColor(if (tv.tag == category) 0xFF8B2E2A.toInt() else Color.TRANSPARENT)
        }
    }

    private fun makeThumb(s: WatermarkSpec): Bitmap {
        val bmp = s.render(s.defaultColor)
        val k = 192f / max(bmp.width, bmp.height)
        val t = Bitmap.createScaledBitmap(
            bmp,
            (bmp.width * k).toInt().coerceAtLeast(1),
            (bmp.height * k).toInt().coerceAtLeast(1),
            true
        )
        if (t !== bmp) bmp.recycle()
        return t
    }

    private fun showCategory(c: Category) {
        category = c
        highlightTabs()
        strip.removeAllViews()
        val specs = WatermarkFactory.all.filter { it.category == c }
        Thread {
            val list = specs.map { s -> thumbs.getOrPut(s.id) { makeThumb(s) } }
            runOnUiThread {
                if (category != c) return@runOnUiThread
                specs.forEachIndexed { i, s ->
                    val iv = ImageView(this).apply {
                        setImageBitmap(list[i])
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        setPadding(dp(8), dp(8), dp(8), dp(8))
                        background = GradientDrawable().apply {
                            cornerRadius = dp(10).toFloat()
                            setColor(0xFF3A3A3A.toInt())
                        }
                        layoutParams = LinearLayout.LayoutParams(dp(84), dp(84)).apply {
                            setMargins(dp(4), dp(6), dp(4), dp(6))
                        }
                        setOnClickListener { addWatermark(s) }
                    }
                    strip.addView(iv)
                }
            }
        }.start()
    }

    private fun addWatermark(s: WatermarkSpec) {
        if (editor.base == null) {
            toast("请先选择一张照片")
            return
        }
        editor.addItem(s, s.defaultColor)
    }

    // ------------------------------------------------------------ 照片读取

    private fun loadPhoto(uri: Uri) {
        Thread {
            val bmp = try {
                decode(uri)
            } catch (e: Exception) {
                null
            }
            runOnUiThread {
                if (bmp == null) toast("无法读取这张照片") else editor.setBase(bmp)
            }
        }.start()
    }

    private fun decode(uri: Uri, maxSide: Int = 2560): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: return null

        val orientation = try {
            contentResolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
                )
            }
        } catch (e: Exception) {
            null
        } ?: ExifInterface.ORIENTATION_NORMAL

        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                m.postRotate(90f); m.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                m.postRotate(270f); m.postScale(-1f, 1f)
            }
            else -> return bmp
        }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }

    // ------------------------------------------------------------ 保存

    private fun save() {
        val bmp = editor.exportBitmap()
        if (bmp == null) {
            toast("请先选择一张照片")
            return
        }
        Thread {
            try {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "古风_${System.currentTimeMillis()}.jpg")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/古风水印")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("无法创建文件")
                contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                contentResolver.update(uri, values, null, null)
                runOnUiThread { toast("已保存到 相册/古风水印") }
            } catch (e: Exception) {
                runOnUiThread { toast("保存失败：${e.message}") }
            }
        }.start()
    }
}
