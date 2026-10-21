package org.fossify.filemanager.dialogs

import android.app.Activity
import androidx.appcompat.app.AlertDialog
import org.fossify.commons.R
import org.fossify.commons.databinding.DialogMessageBinding
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.setupDialogStuff
import org.fossify.filemanager.helpers.applyTcButtonColors

/** Same as commons ConfirmationDialog, but with readable (white in dark theme) buttons. */
class TcConfirmationDialog(
    activity: Activity,
    message: String = "",
    messageId: Int = R.string.proceed_with_deletion,
    positive: Int = R.string.yes,
    negative: Int = R.string.no,
    cancelOnTouchOutside: Boolean = true,
    dialogTitle: String = "",
    val callback: () -> Unit
) {
    private var dialog: AlertDialog? = null

    init {
        val view = DialogMessageBinding.inflate(activity.layoutInflater, null, false)
        view.message.text = message.ifEmpty { activity.resources.getString(messageId) }

        val builder = activity.getAlertDialogBuilder()
            .setPositiveButton(positive) { _, _ -> dialogConfirmed() }

        if (negative != 0) {
            builder.setNegativeButton(negative, null)
        }

        builder.apply {
            activity.setupDialogStuff(
                view.root, this, titleText = dialogTitle, cancelOnTouchOutside = cancelOnTouchOutside
            ) { alertDialog ->
                dialog = alertDialog
                alertDialog.applyTcButtonColors()
            }
        }
    }

    private fun dialogConfirmed() {
        dialog?.dismiss()
        callback()
    }
}
