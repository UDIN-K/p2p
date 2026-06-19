package com.udink.p2p

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
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

    private var serverSocket: ServerSocket? = null
    private var isServerRunning = false

    suspend fun startServer(port: Int = 8988) {
        if (isServerRunning) return
        withContext(Dispatchers.IO) {
            try {
                serverSocket = ServerSocket()
                serverSocket?.reuseAddress = true
                serverSocket?.bind(InetSocketAddress(port))
                isServerRunning = true
                _transferEvents.emit(TransferEvent.ServerStarted)
                
                while (isServerRunning) {
                    val client = serverSocket?.accept() ?: break
                    val clientIp = client.inetAddress.hostAddress
                    if (clientIp != null && peerIp != clientIp) {
                        peerIp = clientIp
                    }
                    launch { handleClient(client) }
                }
            } catch (e: Exception) {
                if (isServerRunning) {
                   _transferEvents.emit(TransferEvent.Error("Server error: ${e.message}"))
                }
            } finally {
                stopServer()
            }
        }
    }

    fun stopServer() {
        isServerRunning = false
        try {
            serverSocket?.close()
        } catch (e: Exception) {}
        serverSocket = null
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
                    
                    FileTransferHelper.copyStream(dataInputStream, fos, totalSize, filename, false, _transferEvents)
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

    suspend fun sendFile(uri: Uri, host: String, port: Int = 8988, overrideFilename: String? = null) {
        withContext(Dispatchers.IO) {
            val socket = Socket()
            try {
                socket.bind(null)
                socket.connect(InetSocketAddress(host, port), 5000)
                
                val dataOutputStream = DataOutputStream(socket.getOutputStream())
                dataOutputStream.writeUTF("FILE")
                
                val filename = overrideFilename ?: FileTransferHelper.getFileName(context, uri)
                dataOutputStream.writeUTF(filename)
                
                val totalSize = FileTransferHelper.getFileSize(context, uri)
                dataOutputStream.writeLong(totalSize)
                
                var inputStream = context.contentResolver.openInputStream(uri)
                if (inputStream == null && uri.path != null) {
                    val file = java.io.File(uri.path!!)
                    if (file.exists()) {
                        inputStream = java.io.FileInputStream(file)
                    }
                }
                
                _transferEvents.emit(TransferEvent.SendingStarted(filename))
                
                if (inputStream != null) {
                    FileTransferHelper.copyStream(inputStream, dataOutputStream, totalSize, filename, true, _transferEvents)
                    inputStream.close()
                }
                
                _transferEvents.emit(TransferEvent.FileSent(filename))
            } catch (e: Exception) {
                _transferEvents.emit(TransferEvent.Error("Send error: ${e.message}"))
            } finally {
                socket.runCatching { close() }
            }
        }
    }
}

sealed class TransferEvent {
    object ServerStarted : TransferEvent()
    object ReceivingStarted : TransferEvent()
    data class FileReceived(val path: String) : TransferEvent()
    data class SendingStarted(val filename: String) : TransferEvent()
    data class FileSent(val filename: String) : TransferEvent()
    data class Progress(val isSending: Boolean, val filename: String, val progress: Float, val speedBytesPerSec: Long = 0L, val etaSeconds: Long = 0L) : TransferEvent()
    data class ChatReceived(val senderIp: String, val message: String) : TransferEvent()
    data class Error(val message: String) : TransferEvent()
}
