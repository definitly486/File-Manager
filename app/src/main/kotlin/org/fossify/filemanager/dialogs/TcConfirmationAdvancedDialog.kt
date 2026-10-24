package org.fossify.filemanager.dialogs

import android.app.Activity
import androidx.appcompat.app.AlertDialog
import org.fossify.commons.R
import org.fossify.commons.databinding.DialogMessageBinding
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.setupDialogStuff
import org.fossify.filemanager.helpers.applyTcButtonColors

/**
 * Same as commons ConfirmationAdvancedDialog, a confirmation with a callback for the negative
 * button too, but with readable (white in dark theme) buttons.
 */
class TcConfirmationAdvancedDialog(
    activity: Activity,
    message: String = "",
    messageId: Int = R.string.proceed_with_deletion,
    positive: Int = R.string.yes,
    negative: Int = R.string.no,
    val cancelOnTouchOutside: Boolean = true,
    val callback: (result: Boolean) -> Unit
) {
    private var dialog: AlertDialog? = null

    init {
        val view = DialogMessageBinding.inflate(activity.layoutInflater, null, false)
        view.message.text = message.ifEmpty { activity.resources.getString(messageId) }

        val builder = activity.getAlertDialogBuilder()
            .setPositiveButton(positive) { _, _ -> positivePressed() }

        if (negative != 0) {
            builder.setNegativeButton(negative) { _, _ -> negativePressed() }
        }

        if (!cancelOnTouchOutside) {
            builder.setOnCancelListener { negativePressed() }
        }

        builder.apply {
            activity.setupDialogStuff(view.root, this, cancelOnTouchOutside = cancelOnTouchOutside) { alertDialog ->
                dialog = alertDialog
                alertDialog.applyTcButtonColors()
            }
        }
    }

    private fun positivePressed() {
        dialog?.dismiss()
        callback(true)
    }

    private fun negativePressed() {
        dialog?.dismiss()
        callback(false)
    }
}
