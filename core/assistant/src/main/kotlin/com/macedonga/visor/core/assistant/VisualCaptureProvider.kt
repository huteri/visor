package com.macedonga.visor.core.assistant

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.OpenableColumns
import java.io.FileNotFoundException
import java.io.IOException

/** Read-only, process-local exports. A persisted Android grant alone never restores capture data. */
class VisualCaptureProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        val app = checkNotNull(context).applicationContext
        ownerContext = app
        // Remove grants from an earlier process, even though its in-memory tokens are already gone.
        app.revokeUriPermission(Uri.parse("content://${app.packageName}.visual"), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return true
    }

    private fun token(uri: Uri): String {
        val app = checkNotNull(context)
        if (uri.scheme != "content" || uri.authority != app.packageName + ".visual" ||
            uri.pathSegments.size != 2 || uri.pathSegments[1] != "visor-visual.bin" ||
            uri.query != null || uri.fragment != null) throw FileNotFoundException("Unknown capture")
        return uri.pathSegments[0]
    }

    private fun bytes(uri: Uri): ByteArray = capture.read(token(uri)) ?: throw FileNotFoundException("Capture expired or deleted")

    override fun getType(uri: Uri): String {
        bytes(uri)
        return MIME
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val size = bytes(uri).size
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns).apply {
            addRow(columns.map { when (it) {
                OpenableColumns.DISPLAY_NAME -> "visor-visual.bin"
                OpenableColumns.SIZE -> size
                else -> null
            } })
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Read only")
        val token = token(uri)
        if (capture.read(token) == null) throw FileNotFoundException("Capture expired or deleted")
        // Pass only the token to the asynchronous writer: Delete cannot leave a queued byte snapshot.
        return openPipeHelper(uri, MIME, null, token) { output, _, _, _, key ->
            ParcelFileDescriptor.AutoCloseOutputStream(output).use { stream ->
                val data = capture.read(key ?: return@use) ?: return@use
                try { stream.write(data) } catch (_: IOException) { /* Receiver closed its pipe. */ }
            }
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException("Read only")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("Read only")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("Read only")

    companion object {
        private const val MIME = "application/octet-stream"
        private var ownerContext: Context? = null
        internal val capture = VisualDiagnostics(now = SystemClock::elapsedRealtime, onRevoke = { token ->
            val app = checkNotNull(ownerContext)
            app.revokeUriPermission(uri(app, token), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })

        private fun uri(context: Context, token: String): Uri = Uri.Builder()
            .scheme("content").authority(context.packageName + ".visual")
            .appendPath(token).appendPath("visor-visual.bin").build()

        internal fun publish(context: Context): Uri? = capture.publish()?.let { uri(context, it) }
    }
}
