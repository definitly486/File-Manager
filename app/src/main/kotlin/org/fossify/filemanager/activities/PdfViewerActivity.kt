package org.fossify.filemanager.activities

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.util.concurrent.Executors

/** Lightweight built-in PDF viewer based on android.graphics.pdf.PdfRenderer. */
class PdfViewerActivity : AppCompatActivity() {
    companion object {
        private const val EXTRA_PATH = "pdf_path"
        fun start(context: Context, path: String) {
            val intent = Intent(context, PdfViewerActivity::class.java).putExtra(EXTRA_PATH, path)
            if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    private var descriptor: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    private val renderExecutor = Executors.newSingleThreadExecutor()
    private val renderLock = Any()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawableResource(android.R.color.black)

        val path = intent.getStringExtra(EXTRA_PATH).orEmpty()
        val uri: Uri? = if (path.isNotEmpty()) Uri.fromFile(File(path)) else intent.data
        if (uri == null) { finish(); return }
        val fileName = path.substringAfterLast('/').ifEmpty { uri.lastPathSegment.orEmpty() }

        try {
            descriptor = if (path.isNotEmpty()) {
                ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)
            } else {
                contentResolver.openFileDescriptor(uri, "r")
            }
            renderer = PdfRenderer(descriptor ?: throw IllegalArgumentException("No descriptor"))
        } catch (_: Exception) {
            // Encrypted/corrupted PDFs end up here (PdfRenderer throws SecurityException / IOException)
            Toast.makeText(this, "Не удалось открыть PDF", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val pageCount = renderer!!.pageCount
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.DKGRAY)
            layoutParams = ViewGroup.LayoutParams(-1, -1)
        }
        val list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@PdfViewerActivity)
            adapter = PageAdapter(pageCount)
            setItemViewCacheSize(2)
        }
        root.addView(list, FrameLayout.LayoutParams(-1, -1))

        val title = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding(16, 12, 16, 12)
            setBackgroundColor(0x99000000.toInt())
            text = "$fileName  •  1 / $pageCount"
        }
        root.addView(title, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        setContentView(root)

        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val first = (rv.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition()
                if (first >= 0) title.text = "$fileName  •  ${first + 1} / $pageCount"
            }
        })
    }

    override fun onDestroy() {
        renderExecutor.shutdownNow()
        synchronized(renderLock) {
            try { renderer?.close() } catch (_: Exception) {}
            try { descriptor?.close() } catch (_: Exception) {}
            renderer = null
        }
        super.onDestroy()
    }

    private inner class PageAdapter(private val count: Int) : RecyclerView.Adapter<PageHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
            val image = ImageView(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(-1, -2)
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
                setBackgroundColor(Color.WHITE)
                setPadding(0, 0, 0, 0)
            }
            val frame = FrameLayout(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(-1, -2)
                setPadding(0, 0, 0, 6)
                addView(image, FrameLayout.LayoutParams(-1, -2))
            }
            return PageHolder(frame, image)
        }

        override fun getItemCount() = count

        override fun onBindViewHolder(holder: PageHolder, position: Int) {
            holder.image.setImageBitmap(null)
            holder.image.tag = position
            val targetWidth = (resources.displayMetrics.widthPixels * 2).coerceAtMost(2400)
            renderExecutor.execute {
                val bitmap = renderPage(position, targetWidth) ?: return@execute
                runOnUiThread {
                    if (holder.image.tag == position) holder.image.setImageBitmap(bitmap)
                }
            }
        }

        override fun onViewRecycled(holder: PageHolder) {
            holder.image.setImageBitmap(null)
        }
    }

    private class PageHolder(view: FrameLayout, val image: ImageView) : RecyclerView.ViewHolder(view)

    private fun renderPage(index: Int, targetWidth: Int): Bitmap? = synchronized(renderLock) {
        val r = renderer ?: return null
        return try {
            r.openPage(index).use { page ->
                val scale = targetWidth.toFloat() / page.width
                val bmp = Bitmap.createBitmap(targetWidth, (page.height * scale).toInt(), Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.WHITE) // PDF pages are transparent by default
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bmp
            }
        } catch (_: Exception) {
            null
        }
    }
}
