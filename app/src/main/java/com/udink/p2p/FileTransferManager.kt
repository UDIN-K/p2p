package com.udink.p2p

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID

class FileTransferManager(private val context: Context) {
    
    private val _transferEvents = MutableSharedFlow<TransferEvent>()
    val transferEvents = _transferEvents.asSharedFlow()

    var peerIp: String? = null

    suspend fun startServer(port: Int = 8988) {
        withContext(Dispatchers.IO) {
            var serverSocket: ServerSocket? = null
            try {
                serverSocket = ServerSocket()
                serverSocket.reuseAddress = true
                serverSocket.bind(InetSocketAddress(port))
                _transferEvents.emit(TransferEvent.ServerStarted)
                
                while (true) {
                    val client = serverSocket.accept()
                    val clientIp = client.inetAddress.hostAddress
                    if (clientIp != null && peerIp != clientIp) {
                        peerIp = clientIp
                    }
                    handleClient(client)
                }
            } catch (e: Exception) {
                _transferEvents.emit(TransferEvent.Error("Server error: ${e.message}"))
            } finally {
                serverSocket?.close()
            }
        }
    }

    private suspend fun handleClient(client: Socket) {
        withContext(Dispatchers.IO) {
            try {
                val dataInputStream = DataInputStream(client.getInputStream())
                val type = dataInputStream.readUTF()
                
                if (type == "PING") {
                    return@withContext
                } else if (type == "CHAT") {
                    val message = dataInputStream.readUTF()
                    val clientIp = client.inetAddress.hostAddress
                    _transferEvents.emit(TransferEvent.ChatReceived(clientIp ?: "Unknown", message))
                } else if (type == "FILE") {
                    val filename = dataInputStream.readUTF()
                    val totalSize = dataInputStream.readLong()
                    
                    val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    val wifiShareDir = File(downloadsDir, "WiFiShare")
                    if (!wifiShareDir.exists()) {
                        wifiShareDir.mkdirs()
                    }
                    
                    val file = File(wifiShareDir, filename)
                    val fos = FileOutputStream(file)
                    
                    _transferEvents.emit(TransferEvent.ReceivingStarted)
                    
                    copyStream(dataInputStream, fos, totalSize, filename, false)
                    fos.close()
                    
                    _transferEvents.emit(TransferEvent.FileReceived(file.absolutePath))
                }
            } catch (e: Exception) {
                _transferEvents.emit(TransferEvent.Error("Receive error: ${e.message}"))
            } finally {
                client.close()
            }
        }
    }

    suspend fun sendPing(host: String, port: Int = 8988) {
        withContext(Dispatchers.IO) {
            val socket = Socket()
            try {
                socket.bind(null)
                socket.connect(InetSocketAddress(host, port), 5000)
                val dataOutputStream = DataOutputStream(socket.getOutputStream())
                dataOutputStream.writeUTF("PING")
                dataOutputStream.flush()
            } catch (e: Exception) {
                // ignore ping error
            } finally {
                socket.runCatching { close() }
            }
        }
    }

    suspend fun sendChat(message: String, host: String, port: Int = 8988) {
        withContext(Dispatchers.IO) {
            val socket = Socket()
            try {
                socket.bind(null)
                socket.connect(InetSocketAddress(host, port), 5000)
                val dataOutputStream = DataOutputStream(socket.getOutputStream())
                dataOutputStream.writeUTF("CHAT")
                dataOutputStream.writeUTF(message)
                dataOutputStream.flush()
            } catch (e: Exception) {
               _transferEvents.emit(TransferEvent.Error("Chat error: ${e.message}"))
            } finally {
                socket.runCatching { close() }
            }
        }
    }

    suspend fun sendFile(uri: Uri, host: String, port: Int = 8988) {
        withContext(Dispatchers.IO) {
            val socket = Socket()
            try {
                socket.bind(null)
                socket.connect(InetSocketAddress(host, port), 5000)
                
                val dataOutputStream = DataOutputStream(socket.getOutputStream())
                dataOutputStream.writeUTF("FILE")
                
                val filename = getFileName(uri)
                dataOutputStream.writeUTF(filename)
                
                val totalSize = getFileSize(uri)
                dataOutputStream.writeLong(totalSize)
                
                val inputStream = context.contentResolver.openInputStream(uri)
                
                _transferEvents.emit(TransferEvent.SendingStarted)
                
                if (inputStream != null) {
                    copyStream(inputStream, dataOutputStream, totalSize, filename, true)
                    inputStream.close()
                }
                
                _transferEvents.emit(TransferEvent.FileSent)
            } catch (e: Exception) {
                _transferEvents.emit(TransferEvent.Error("Send error: ${e.message}"))
            } finally {
                socket.runCatching { close() }
            }
        }
    }

    @SuppressLint("Range")
    private fun getFileName(uri: Uri): String {
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
    private fun getFileSize(uri: Uri): Long {
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

    private suspend fun copyStream(input: InputStream, output: OutputStream, totalSize: Long, filename: String, isSending: Boolean) {
        val buffer = ByteArray(1024 * 8)
        var bytesRead: Int
        var totalRead = 0L
        var lastProgressEmit = 0L
        while (input.read(buffer).also { bytesRead = it } != -1) {
            output.write(buffer, 0, bytesRead)
            totalRead += bytesRead
            if (totalSize > 0) {
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastProgressEmit > 100) { 
                    lastProgressEmit = currentTime
                    _transferEvents.emit(TransferEvent.Progress(isSending, filename, (totalRead.toFloat() / totalSize.toFloat()).coerceIn(0f, 1f)))
                }
            }
        }
        // Emit 100% when done
        _transferEvents.emit(TransferEvent.Progress(isSending, filename, 1f))
        output.flush()
    }
}

sealed class TransferEvent {
    object ServerStarted : TransferEvent()
    object ReceivingStarted : TransferEvent()
    data class FileReceived(val path: String) : TransferEvent()
    object SendingStarted : TransferEvent()
    object FileSent : TransferEvent()
    data class Progress(val isSending: Boolean, val filename: String, val progress: Float) : TransferEvent()
    data class ChatReceived(val senderIp: String, val message: String) : TransferEvent()
    data class Error(val message: String) : TransferEvent()
}
