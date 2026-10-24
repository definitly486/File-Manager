package org.fossify.filemanager.helpers

import android.graphics.PorterDuff
import android.widget.ImageView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.RecyclerView
import org.fossify.commons.adapters.FilepickerItemsAdapter
import org.fossify.commons.dialogs.FilePickerDialog

/** Classic yellow folder color. */
private const val FOLDER_YELLOW = 0xFFFFCA28.toInt()

/**
 * Restyles the folder picker of fossify commons, which paints its buttons and folder icons with
 * primaryColor, almost the same color as the dialog background in the dark Total Commander theme:
 * - OK and Cancel buttons get the theme text color (white in the dark theme)
 * - folder icons become yellow
 *
 * The picker dialog is shown as soon as it is constructed, so call this right after creating it.
 * The commons library does not expose its dialog, hence the reflection. It is pinned to a given
 * version and any failure just leaves the default look.
 */
fun FilePickerDialog.applyTcPickerStyle() {
    try {
        val field = FilePickerDialog::class.java.getDeclaredField("mDialog").apply { isAccessible = true }
        val dialog = field.get(this) as? AlertDialog ?: return

        dialog.applyTcButtonColors()

        val list = dialog.findViewById<RecyclerView>(org.fossify.commons.R.id.filepicker_list) ?: return

        fun style(child: android.view.View) {
            val icon = child.findViewById<ImageView>(org.fossify.commons.R.id.list_item_icon) ?: return
            val position = list.getChildAdapterPosition(child)
            val items = (list.adapter as? FilepickerItemsAdapter)?.fileDirItems ?: return
            val item = items.getOrNull(position) ?: return
            if (item.isDirectory) {
                icon.setColorFilter(FOLDER_YELLOW, PorterDuff.Mode.SRC_IN)
                icon.imageAlpha = 255
            } else {
                // Holders get recycled, do not leave a yellow tint on file icons
                icon.clearColorFilter()
            }
        }

        // Items are bound before they get attached, and the list is refilled on every folder change
        list.addOnChildAttachStateChangeListener(object : RecyclerView.OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(view: android.view.View) = style(view)
            override fun onChildViewDetachedFromWindow(view: android.view.View) {}
        })
        for (i in 0 until list.childCount) style(list.getChildAt(i))
    } catch (e: Exception) {
        // Keep the default look
    }
}
