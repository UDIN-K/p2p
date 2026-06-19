package com.udink.p2p

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.flow.MutableSharedFlow
import java.io.InputStream
import java.io.OutputStream

object FileTransferHelper {

    @SuppressLint("Range")
    fun getFileName(context: Context, uri: Uri): String {
        var result: String? = null
        if (uri.scheme == "content") {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    result = cursor.getString(cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME))
                }
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/') ?: -1
            if (cut != -1) {
                result = result?.substring(cut + 1)
            }
        }
        return result ?: "Received_${System.currentTimeMillis()}"
    }

    @SuppressLint("Range")
    fun getFileSize(context: Context, uri: Uri): Long {
        var result: Long = -1
        if (uri.scheme == "content") {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex != -1) {
                        if (!cursor.isNull(sizeIndex)) {
                            result = cursor.getLong(sizeIndex)
                        }
                    }
                }
            }
        }
        if (result == -1L) {
            try {
                context.contentResolver.openFileDescriptor(uri, "r")?.use {
                    result = it.statSize
                }
            } catch (e: Exception) {
                // Ignore
            }
        }
        return if (result <= 0L) 0L else result // 0 means unknown length
    }

    suspend fun copyStream(input: InputStream, output: OutputStream, totalSize: Long, filename: String, isSending: Boolean, events: MutableSharedFlow<TransferEvent>) {
        val buffer = ByteArray(1024 * 8)
        var bytesRead: Int
        var totalRead = 0L
        var lastProgressEmit = 0L
        var lastBytesRead = 0L
        var lastTimeForSpeed = System.currentTimeMillis()
        var currentSpeed = 0L // bytes per sec
        var etaSeconds = 0L
        
        while (input.read(buffer).also { bytesRead = it } != -1) {
            output.write(buffer, 0, bytesRead)
            totalRead += bytesRead
            if (totalSize > 0) {
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastProgressEmit > 200) { 
                    lastProgressEmit = currentTime
                    val timeDiff = currentTime - lastTimeForSpeed
                    if (timeDiff >= 1000) { 
                        currentSpeed = ((totalRead - lastBytesRead) * 1000) / timeDiff
                        if (currentSpeed > 0) {
                            etaSeconds = (totalSize - totalRead) / currentSpeed
                        }
                        lastBytesRead = totalRead
                        lastTimeForSpeed = currentTime
                    } else if (currentSpeed == 0L && timeDiff > 0) {
                        currentSpeed = ((totalRead - lastBytesRead) * 1000) / timeDiff
                        if (currentSpeed > 0) {
                            etaSeconds = (totalSize - totalRead) / currentSpeed
                        }
                    }
                    events.emit(TransferEvent.Progress(isSending, filename, (totalRead.toFloat() / totalSize.toFloat()).coerceIn(0f, 1f), currentSpeed, etaSeconds))
                }
            }
        }
        // Emit 100% when done
        events.emit(TransferEvent.Progress(isSending, filename, 1f, 0L, 0L))
        output.flush()
    }
}
