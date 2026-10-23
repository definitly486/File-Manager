package org.fossify.filemanager.activities

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File

/** Lightweight built-in image viewer for common image formats. */
class ImageViewerActivity : AppCompatActivity() {
    companion object {
        private const val EXTRA_PATH = "image_path"
        fun start(context: Context, path: String) {
            val intent = Intent(context, ImageViewerActivity::class.java).putExtra(EXTRA_PATH, path)
            if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawableResource(android.R.color.black)
        val path = intent.getStringExtra(EXTRA_PATH).orEmpty()
        val uri = if (path.isNotEmpty()) Uri.fromFile(File(path)) else intent.data
        if (uri == null) { finish(); return }

        val root = FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            layoutParams = ViewGroup.LayoutParams(-1, -1)
        }
        val image = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(-1, -1)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = path.substringAfterLast('/').ifEmpty { uri.lastPathSegment.orEmpty() }
        }
        root.addView(image)
        val title = TextView(this).apply {
            text = path.substringAfterLast('/').ifEmpty { uri.lastPathSegment.orEmpty() }
            setTextColor(android.graphics.Color.WHITE)
            textSize = 15f
            setPadding(16, 12, 16, 12)
            setBackgroundColor(0x99000000.toInt())
        }
        root.addView(title, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        setContentView(root)

        try {
            val bitmap = if (path.isNotEmpty()) BitmapFactory.decodeFile(path)
                else contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
            if (bitmap == null) throw IllegalArgumentException("Unsupported or unreadable image")
            image.setImageBitmap(bitmap)
            title.text = "${title.text}  •  ${bitmap.width} × ${bitmap.height}"
        } catch (_: Exception) {
            Toast.makeText(this, "Не удалось открыть изображение", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
