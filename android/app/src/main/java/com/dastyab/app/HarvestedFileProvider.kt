package com.dastyab.app

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/**
 * Self-contained read-only [ContentProvider] serving downloaded VPN config files
 * (`.ovpn`, `.openvpn`, `.npvs`, `.npv4`, `.hc`, `.custom`, `.costum`, `.ehi`, `.conf`, `.dark`, `.hat`, `.tls`)
 * with accurate display names & extensions so external VPN apps import them directly.
 */
class HarvestedFileProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "com.dastyab.app.filevault"

        fun uriForFile(file: File): Uri {
            return Uri.Builder()
                .scheme("content")
                .authority(AUTHORITY)
                .appendPath(file.name)
                .build()
        }
    }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        val file = resolveFile(uri) ?: return null
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cursor = MatrixCursor(cols, 1)
        val row = Array<Any?>(cols.size) { idx ->
            when (cols[idx]) {
                OpenableColumns.DISPLAY_NAME -> file.name
                OpenableColumns.SIZE -> file.length()
                else -> null
            }
        }
        cursor.addRow(row)
        return cursor
    }

    override fun getType(uri: Uri): String {
        val ext = uri.lastPathSegment?.substringAfterLast('.', "")?.lowercase().orEmpty()
        return ConfigFileHarvester.mimeTypeForExtension(ext)
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val file = resolveFile(uri) ?: throw FileNotFoundException("Vault file not found: $uri")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    private fun resolveFile(uri: Uri): File? {
        val ctx = context ?: return null
        val segment = uri.lastPathSegment?.trim().orEmpty()
        if (segment.isEmpty() || segment.contains("..") || segment.contains('/') || segment.contains('\\')) {
            return null
        }
        val vaultDir = File(ctx.filesDir, ConfigFileHarvester.VAULT_DIR_NAME)
        val candidate = File(vaultDir, segment)
        if (!candidate.isFile) return null
        val canonicalVault = runCatching { vaultDir.canonicalPath }.getOrNull() ?: return null
        val canonicalFile = runCatching { candidate.canonicalPath }.getOrNull() ?: return null
        return if (canonicalFile.startsWith("$canonicalVault/")) candidate else null
    }
}
