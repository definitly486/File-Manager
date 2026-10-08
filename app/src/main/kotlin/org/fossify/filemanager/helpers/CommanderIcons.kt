package org.fossify.filemanager.helpers

import android.content.Context
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import org.fossify.filemanager.R

/**
 * File-type placeholder icons in the Total Commander style.
 * Overrides the default placeholders for the most common extensions;
 * all other extensions keep whatever the commons library provides.
 */
object CommanderIcons {
    private val AUDIO = listOf("mp3", "wav", "flac", "ogg", "oga", "m4a", "aac", "opus", "wma", "mid", "midi", "amr")
    private val ARCHIVES = listOf("zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz", "jar", "cab", "iso")
    private val IMAGES = listOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "tif", "tiff", "svg", "raw")
    private val APPS = listOf("apk", "xapk", "apks", "aab")

    fun applyFileTypeIcons(context: Context, target: MutableMap<String, Drawable>) {
        mapOf(
            R.drawable.tc_music to AUDIO,
            R.drawable.tc_zip to ARCHIVES,
            R.drawable.tc_image to IMAGES,
            R.drawable.tc_apps to APPS,
        ).forEach { (drawableId, extensions) ->
            val drawable = ContextCompat.getDrawable(context, drawableId) ?: return@forEach
            extensions.forEach { target[it] = drawable }
        }
    }
}
