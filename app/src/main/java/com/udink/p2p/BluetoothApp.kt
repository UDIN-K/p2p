package com.udink.p2p

import android.Manifest
import android.content.Context
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class)
@Composable
fun BluetoothApp(
    modifier: Modifier = Modifier,
    mode: String,
    bluetoothManager: MyBluetoothManager,
    transferEvents: SharedFlow<TransferEvent>,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val isBluetoothEnabled by bluetoothManager.isBluetoothEnabled.collectAsState()
    val peers by bluetoothManager.peers.collectAsState()
    val connectionStatus by bluetoothManager.connectionStatus.collectAsState()
    val chatMessages = remember { mutableStateListOf<Pair<String, String>>() }
    var chatInput by remember { mutableStateOf("") }
    var transferState by remember { mutableStateOf(TransferState()) }

    LaunchedEffect(Unit) {
        transferEvents.collect { event ->
            if (event is TransferEvent.ChatReceived) {
                chatMessages.add(Pair(event.senderIp, event.message)) // senderIp holds sender name
            } else if (event is TransferEvent.ReceivingStarted) {
                transferState = TransferState(isActive = true, isSending = false, filename = "Incoming File...", progress = 0f, speedBytesPerSec = 0L, etaSeconds = 0L)
            } else if (event is TransferEvent.SendingStarted) {
                transferState = TransferState(isActive = true, isSending = true, filename = "Outgoing File...", progress = 0f, speedBytesPerSec = 0L, etaSeconds = 0L)
            } else if (event is TransferEvent.Progress) {
                transferState = TransferState(
                    isActive = true,
                    isSending = event.isSending,
                    filename = event.filename,
                    progress = event.progress,
                    speedBytesPerSec = event.speedBytesPerSec,
                    etaSeconds = event.etaSeconds
                )
                if (event.progress >= 1f) {
                    kotlinx.coroutines.delay(2000)
                    transferState = transferState.copy(isActive = false)
                }
            } else if (event is TransferEvent.Error) {
                transferState = transferState.copy(isActive = false)
            }
        }
    }

    val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        listOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
    } else {
        listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN
        )
    }

    val permissionsState = rememberMultiplePermissionsState(permissions = permissions)

    LaunchedEffect(permissionsState.allPermissionsGranted) {
        if (permissionsState.allPermissionsGranted) {
             bluetoothManager.startServer()
             bluetoothManager.discoverPeers { err -> Toast.makeText(context, err, Toast.LENGTH_SHORT).show() }
        } else {
            permissionsState.launchMultiplePermissionRequest()
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            if (connectionStatus.startsWith("Connected")) {
                (context as? ComponentActivity)?.lifecycleScope?.launch {
                    bluetoothManager.sendFile(it)
                }
            } else {
                Toast.makeText(context, "Not connected to any device.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (mode == "chat") "Bluetooth Chat" else "Bluetooth Transfer") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            
            // Permissions Banner
            if (!permissionsState.allPermissionsGranted) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Missing Bluetooth/Location permissions", color = MaterialTheme.colorScheme.onErrorContainer)
                        Button(onClick = { permissionsState.launchMultiplePermissionRequest() }, modifier = Modifier.padding(top = 8.dp)) {
                            Text("Grant")
                        }
                    }
                }
            }

            if (!isBluetoothEnabled) {
               Card(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Text("Bluetooth is off. Please enable it in system settings.", modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onErrorContainer)
                } 
            }

            Text("Status: $connectionStatus", modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

            if (transferState.isActive) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
                        Text(
                            text = if (transferState.isSending) "Sending ${transferState.filename}" else "Receiving ${transferState.filename}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        LinearProgressIndicator(
                            progress = { transferState.progress },
                            modifier = Modifier.fillMaxWidth().height(8.dp),
                            strokeCap = androidx.compose.ui.graphics.StrokeCap.Round
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "${(transferState.progress * 100).toInt()}%",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.align(Alignment.End),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (connectionStatus.startsWith("Connected")) {
                if (mode == "chat") {
                    LazyColumn(
                        modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
                        contentPadding = PaddingValues(vertical = 16.dp)
                    ) {
                        items(chatMessages) { msg ->
                            val isMe = msg.first == "Me"
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                contentAlignment = if (isMe) Alignment.CenterEnd else Alignment.CenterStart
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(16.dp),
                                    color = if (isMe) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer,
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Text(msg.first, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(msg.second, style = MaterialTheme.typography.bodyLarge)
                                    }
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = chatInput,
                            onValueChange = { chatInput = it },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("Message...") },
                            shape = RoundedCornerShape(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        IconButton(
                            onClick = {
                                if (chatInput.isNotBlank()) {
                                    val msg = chatInput
                                    chatMessages.add(Pair("Me", msg))
                                    chatInput = ""
                                    (context as? ComponentActivity)?.lifecycleScope?.launch {
                                        bluetoothManager.sendChat(msg)
                                    }
                                }
                            },
                            modifier = Modifier.background(MaterialTheme.colorScheme.primary, CircleShape)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = MaterialTheme.colorScheme.onPrimary)
                        }
                    }
                } else {
                    Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Button(
                            onClick = { filePickerLauncher.launch("*/*") },
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Text("Select File to Send")
                        }
                    }  
                }
            } else {
                Text(
                    text = "Discovered / Paired Devices",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )

                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(peers) { peer ->
                        @Suppress("MissingPermission")
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            onClick = { 
                                Toast.makeText(context, "Connecting to ${peer.name ?: "Device"}", Toast.LENGTH_SHORT).show()
                                bluetoothManager.connect(peer) { err -> Toast.makeText(context, err, Toast.LENGTH_SHORT).show() }
                            }
                        ) {
                            Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Person, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.width(16.dp))
                                Column {
                                    Text(text = peer.name ?: "Unknown Device", style = MaterialTheme.typography.titleMedium)
                                    Text(text = peer.address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
