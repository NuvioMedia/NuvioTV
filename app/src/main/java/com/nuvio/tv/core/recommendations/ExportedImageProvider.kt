package com.nuvio.tv.core.recommendations

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException

class ExportedImageProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String = "image/jpeg"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    @Throws(FileNotFoundException::class)
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val fileName = uri.lastPathSegment ?: throw FileNotFoundException()
        if (fileName.contains("..") || fileName.contains("/")) throw FileNotFoundException()
        
        val cacheDir = context?.cacheDir ?: throw FileNotFoundException()
        val tvProgressDir = File(cacheDir, "tv_progress")
        val file = File(tvProgressDir, fileName)
        
        if (!file.exists()) throw FileNotFoundException(file.absolutePath)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }
}
