package org.fossify.filemanager.helpers

import android.app.Dialog
import androidx.appcompat.app.AlertDialog

/**
 * Paints the dialog buttons with the theme text color (white in the dark Total Commander theme)
 * instead of primaryColor, which is almost the same color as the dialog background.
 * Must be called after the dialog is shown (fossify commons sets the button colors in setupDialogStuff).
 */
fun AlertDialog.applyTcButtonColors() {
    val color = TcTheme.text(context)
    intArrayOf(Dialog.BUTTON_POSITIVE, Dialog.BUTTON_NEGATIVE, Dialog.BUTTON_NEUTRAL).forEach { which ->
        getButton(which)?.setTextColor(color)
    }
}
