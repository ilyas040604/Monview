package com.monview

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

object PhotoStorage {

    fun save(
        context: Context,
        bitmap: Bitmap
    ): Boolean {
        val fileName = "MonView_${System.currentTimeMillis()}.jpg"

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveModern(context, bitmap, fileName)
        } else {
            saveLegacy(context, bitmap, fileName)
        }
    }

    private fun saveModern(
        context: Context,
        bitmap: Bitmap,
        fileName: String
    ): Boolean {
        val values = ContentValues().apply {
            put(
                MediaStore.Images.Media.DISPLAY_NAME,
                fileName
            )
            put(
                MediaStore.Images.Media.MIME_TYPE,
                "image/jpeg"
            )
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                "${Environment.DIRECTORY_PICTURES}/MonView"
            )
            put(
                MediaStore.MediaColumns.IS_PENDING,
                1
            )
        }

        val uri = context.contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            values
        ) ?: return false

        return try {
            val output = context.contentResolver.openOutputStream(uri, "w")
                ?: throw IOException("Tidak dapat membuka file foto")

            val compressed = output.use {
                bitmap.compress(
                    Bitmap.CompressFormat.JPEG,
                    95,
                    it
                )
            }

            if (!compressed) {
                throw IOException("Gagal mengubah foto menjadi JPEG")
            }

            val completedValues = ContentValues().apply {
                put(
                    MediaStore.MediaColumns.IS_PENDING,
                    0
                )
            }

            val updated = context.contentResolver.update(
                uri,
                completedValues,
                null,
                null
            )

            if (updated <= 0) {
                throw IOException("Gagal menyelesaikan penyimpanan foto")
            }

            true

        } catch (_: Exception) {
            context.contentResolver.delete(uri, null, null)
            false
        }
    }

    @Suppress("DEPRECATION")
    private fun saveLegacy(
        context: Context,
        bitmap: Bitmap,
        fileName: String
    ): Boolean {
        val picturesDirectory =
            Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_PICTURES
            )

        val photoDirectory = File(picturesDirectory, "MonView")

        if (!photoDirectory.exists() && !photoDirectory.mkdirs()) {
            return false
        }

        val photoFile = File(photoDirectory, fileName)

        return try {
            val compressed = FileOutputStream(photoFile).use {
                bitmap.compress(
                    Bitmap.CompressFormat.JPEG,
                    95,
                    it
                )
            }

            if (!compressed) {
                throw IOException("Gagal menyimpan foto")
            }

            MediaScannerConnection.scanFile(
                context,
                arrayOf(photoFile.absolutePath),
                arrayOf("image/jpeg"),
                null
            )

            true

        } catch (_: Exception) {
            photoFile.delete()
            false
        }
    }
}