package com.colink.android.ui.transfers

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import com.colink.android.R
import com.colink.android.domain.model.FileTransfer
import java.io.File

fun openTransferFile(context: Context, transfer: FileTransfer) {
    val rawUri = transfer.localUri?.takeIf { it.isNotBlank() } ?: return
    val parsed = Uri.parse(rawUri)
    val uri = when (parsed.scheme) {
        "content" -> parsed
        "file" -> FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            File(parsed.path.orEmpty()),
        )
        else -> FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            File(rawUri),
        )
    }
    val mimeType = if (uri.scheme == "content") {
        context.contentResolver.getType(uri)
    } else {
        null
    } ?: "*/*"
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, mimeType)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching {
        context.startActivity(
            Intent.createChooser(
                intent,
                transfer.fileName.ifBlank { context.getString(R.string.unnamed_file) },
            ),
        )
    }.onFailure {
        Toast.makeText(context, R.string.toast_open_file_failed, Toast.LENGTH_SHORT).show()
    }
}
