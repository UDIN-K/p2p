package com.udink.p2p

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class MyBluetoothManager(private val context: Context, private val transferEvents: MutableSharedFlow<TransferEvent>) {

    private val bluetoothManager: BluetoothManager? = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager?
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

    private val _isBluetoothEnabled = MutableStateFlow(bluetoothAdapter?.isEnabled == true)
    val isBluetoothEnabled = _isBluetoothEnabled.asStateFlow()

    private val _peers = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    val peers = _peers.asStateFlow()
    
    private val _connectionStatus = MutableStateFlow<String>("Disconnected")
    val connectionStatus = _connectionStatus.asStateFlow()

    private var activeSocket: BluetoothSocket? = null
    private var serverThread: ServerThread? = null

    companion object {
        private const val APP_NAME = "p2p"
        private val MY_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB") // Well-known SPP UUID
    }

    private val receiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                    _isBluetoothEnabled.value = (state == BluetoothAdapter.STATE_ON)
                }
                BluetoothDevice.ACTION_FOUND -> {
                    val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    if (device != null) {
                        _peers.update { currentPeers ->
                            if (currentPeers.any { it.address == device.address }) {
                                currentPeers
                            } else {
                                currentPeers + device
                            }
                        }
                    }
                }
            }
        }
    }

    private var isReceiverRegistered = false

    fun registerReceiver() {
        if (isReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_FOUND)
        }
        try {
            context.registerReceiver(receiver, filter)
            isReceiverRegistered = true
        } catch (e: Exception) {}
        _isBluetoothEnabled.value = bluetoothAdapter?.isEnabled == true
        if (hasPermission()) {
            getPairedDevices()
        }
    }

    @SuppressLint("MissingPermission")
    fun unregisterReceiver() {
        if (isReceiverRegistered) {
            try {
                context.unregisterReceiver(receiver)
                isReceiverRegistered = false
            } catch (e: Exception) {}
        }
        if (hasPermission() && bluetoothAdapter?.isDiscovering == true) {
            bluetoothAdapter.cancelDiscovery()
        }
        stopServer()
        activeSocket?.close()
    }

    private fun hasPermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        }
        return true
    }

    @SuppressLint("MissingPermission")
    fun getPairedDevices() {
        if (!hasPermission() || bluetoothAdapter == null) return
        val pairedDevices: Set<BluetoothDevice>? = bluetoothAdapter.bondedDevices
        pairedDevices?.let { devices ->
            _peers.value = devices.toList()
        }
    }

    @SuppressLint("MissingPermission")
    fun discoverPeers(onError: (String) -> Unit) {
        if (!hasPermission() || bluetoothAdapter == null) {
            onError("Permissions not granted or Bluetooth not supported")
            return
        }
        if (bluetoothAdapter.isDiscovering) {
            bluetoothAdapter.cancelDiscovery()
        }
        bluetoothAdapter.startDiscovery()
    }

    // SERVER LOGIC
    fun startServer() {
        if (!hasPermission() || bluetoothAdapter == null) return
        stopServer()
        serverThread = ServerThread().apply { start() }
    }

    fun stopServer() {
        serverThread?.cancel()
        serverThread = null
    }

    fun disconnect() {
        try {
            activeSocket?.close()
        } catch (e: Exception) {}
        activeSocket = null
        _connectionStatus.value = "Disconnected"
        // Resume server listen state so we can receive again
        startServer()
    }

    @SuppressLint("MissingPermission")
    private inner class ServerThread : Thread() {
        private var mmServerSocket: BluetoothServerSocket? = null
        
        init {
            try {
                mmServerSocket = bluetoothAdapter?.listenUsingRfcommWithServiceRecord(APP_NAME, MY_UUID)
            } catch (e: Exception) {}
        }

        override fun run() {
            var shouldLoop = true
            while (shouldLoop) {
                val socket: BluetoothSocket? = try {
                    mmServerSocket?.accept()
                } catch (e: Exception) {
                    shouldLoop = false
                    null
                }
                socket?.also {
                    activeSocket = it
                    _connectionStatus.value = "Connected to peer"
                    listenClient(it)
                }
            }
        }

        fun cancel() {
            try {
                mmServerSocket?.close()
            } catch (e: Exception) {
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice, onError: (String) -> Unit = {}) {
        if (!hasPermission() || bluetoothAdapter == null) {
            onError("Permissions missing")
            return
        }
        bluetoothAdapter.cancelDiscovery()
        ConnectThread(device, onError).start()
    }

    @SuppressLint("MissingPermission")
    private inner class ConnectThread(val device: BluetoothDevice, val onError: (String) -> Unit) : Thread() {
        
        private var mmSocket: BluetoothSocket? = null

        init {
            try {
                mmSocket = device.createRfcommSocketToServiceRecord(MY_UUID)
            } catch (e: Exception) {}
        }

        override fun run() {
            var attempt = 0
            var connected = false
            while (attempt < 3 && !connected) {
                try {
                    mmSocket?.connect()
                    connected = true
                } catch (e: Exception) {
                    attempt++
                    if (attempt == 3) {
                        try {
                            mmSocket?.close()
                        } catch (e2: Exception) {}
                        val msg = e.message ?: "Target device may not be ready"
                        onError("Connection failed: $msg")
                        return
                    }
                    try { Thread.sleep(1500) } catch (e3: Exception) {}
                }
            }
            if (connected) {
                mmSocket?.let { 
                    activeSocket = it
                    val deviceName = try { device.name ?: device.address } catch (e: SecurityException) { device.address }
                    _connectionStatus.value = "Connected to $deviceName"
                    listenClient(it)
                }
            }
        }

        fun cancel() {
            try {
                mmSocket?.close()
            } catch (e: Exception) {}
        }
    }

    @SuppressLint("MissingPermission")
    private fun listenClient(socket: BluetoothSocket) {
        kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
            try {
                val dataInputStream = DataInputStream(socket.inputStream)
                while (true) {
                    val type = dataInputStream.readUTF()
                    if (type == "CHAT") {
                        val message = dataInputStream.readUTF()
                        val senderName = socket.remoteDevice.name ?: "Bluetooth Master"
                        transferEvents.emit(TransferEvent.ChatReceived(senderName, message))
                    } else if (type == "FILE") {
                        val filename = dataInputStream.readUTF()
                        val totalSize = dataInputStream.readLong()
                        
                        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                        val btShareDir = File(downloadsDir, "BTShare")
                        if (!btShareDir.exists()) {
                            btShareDir.mkdirs()
                        }
                        val file = File(btShareDir, filename)
                        val fos = FileOutputStream(file)
                        
                        transferEvents.emit(TransferEvent.ReceivingStarted)
                        
                        FileTransferHelper.copyStream(socket.inputStream, fos, totalSize, filename, false, transferEvents)
                        fos.close()
                        
                        transferEvents.emit(TransferEvent.FileReceived(file.absolutePath))
                    }
                }
            } catch (e: Exception) {
                activeSocket = null
                _connectionStatus.value = "Disconnected"
            }
        }
    }

    suspend fun sendChat(message: String) {
        withContext(Dispatchers.IO) {
            val socket = activeSocket ?: return@withContext
            try {
                val dataOutputStream = DataOutputStream(socket.outputStream)
                dataOutputStream.writeUTF("CHAT")
                dataOutputStream.writeUTF(message)
                dataOutputStream.flush()
            } catch (e: Exception) {
               transferEvents.emit(TransferEvent.Error("Chat error: ${e.message}"))
            }
        }
    }

    suspend fun sendFile(uri: Uri, overrideFilename: String? = null) {
        withContext(Dispatchers.IO) {
            val socket = activeSocket ?: return@withContext
            try {
                val dataOutputStream = DataOutputStream(socket.outputStream)
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
                
                transferEvents.emit(TransferEvent.SendingStarted(filename))
                
                if (inputStream != null) {
                    FileTransferHelper.copyStream(inputStream, dataOutputStream, totalSize, filename, true, transferEvents)
                    inputStream.close()
                }
                
                transferEvents.emit(TransferEvent.FileSent(filename))
            } catch (e: Exception) {
                transferEvents.emit(TransferEvent.Error("Send error: ${e.message}"))
            }
        }
    }
}
