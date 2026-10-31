package org.blueFrontier.devSideCar.next.android

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException

/**
 * Serves the kernel log to a share target.
 *
 * A `file://` URI is rejected by every current share target, and the app has no
 * AndroidX, so FileProvider is unavailable; a ContentProvider of our own is the
 * smallest thing that yields a `content://` URI the receiver may read after we
 * grant it. Only kernel.log is exposed.
 */
class LogProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("read-only provider")
        if (uri.lastPathSegment != LOG_NAME) throw FileNotFoundException("unknown file: $uri")
        val dir = context?.filesDir ?: throw FileNotFoundException("no files dir")
        val file = File(dir, LOG_NAME)
        if (!file.exists()) throw FileNotFoundException(file.absolutePath)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String = "text/plain"

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    companion object {
        const val LOG_NAME = "kernel.log"
        const val AUTHORITY_SUFFIX = ".logs"

        fun uriFor(context: android.content.Context): Uri =
            Uri.parse("content://${context.packageName}$AUTHORITY_SUFFIX/$LOG_NAME")
    }
}
