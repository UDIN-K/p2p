package com.udink.p2p

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.draw.scale
import androidx.compose.animation.core.*
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.udink.p2p.ui.theme.MyApplicationTheme
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var wifiP2pManager: WifiP2pManager
    private lateinit var channel: WifiP2pManager.Channel
    private lateinit var wifiDirectManager: WiFiDirectManager
    private lateinit var fileTransferManager: FileTransferManager
    private lateinit var myBluetoothManager: MyBluetoothManager
    private val btTransferEvents = kotlinx.coroutines.flow.MutableSharedFlow<TransferEvent>(extraBufferCapacity = 10)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        wifiP2pManager = getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
        channel = wifiP2pManager.initialize(this, mainLooper, null)
        wifiDirectManager = WiFiDirectManager(this, wifiP2pManager, channel)
        fileTransferManager = FileTransferManager(this)
        myBluetoothManager = MyBluetoothManager(this, btTransferEvents)

        setContent {
            MyApplicationTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    AppNavigation(
                        modifier = Modifier.padding(innerPadding),
                        wifiDirectManager = wifiDirectManager,
                        fileTransferManager = fileTransferManager,
                        bluetoothManager = myBluetoothManager,
                        btTransferEvents = btTransferEvents
                    )
                }
            }
        }
        
        lifecycleScope.launch {
            fileTransferManager.transferEvents.collect { event ->
                val message = when (event) {
                    is TransferEvent.Error -> event.message
                    is TransferEvent.FileReceived -> "File saved: ${event.path}"
                    is TransferEvent.FileSent -> "File sent successfully: ${event.filename}"
                    // ... other events can stay silent in global toast to avoid spam
                    else -> null
                }
                if (message != null) {
                    Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
                }
            }
        }
        
        lifecycleScope.launch {
            btTransferEvents.collect { event ->
                val message = when (event) {
                    is TransferEvent.Error -> "BT Error: ${event.message}"
                    is TransferEvent.FileReceived -> "BT File saved: ${event.path}"
                    is TransferEvent.FileSent -> "BT File sent successfully: ${event.filename}"
                    else -> null
                }
                if (message != null) {
                    Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private var isWifiReceiverRegistered = false

    override fun onResume() {
        super.onResume()
        if (!isWifiReceiverRegistered) {
            try {
                registerReceiver(wifiDirectManager.receiver, wifiDirectManager.intentFilter)
                isWifiReceiverRegistered = true
            } catch (e: Exception) {}
        }
        myBluetoothManager.registerReceiver()
    }

    override fun onPause() {
        super.onPause()
        if (isWifiReceiverRegistered) {
            try {
                unregisterReceiver(wifiDirectManager.receiver)
                isWifiReceiverRegistered = false
            } catch (e: Exception) {}
        }
        try { wifiDirectManager.stopDiscovery() } catch (e: Exception) {}
        myBluetoothManager.unregisterReceiver()
    }

    override fun onDestroy() {
        super.onDestroy()
        fileTransferManager.stopServer()
        // Ensure group is removed to cleanup wifi direct state
        try {
            wifiP2pManager.removeGroup(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {}
                override fun onFailure(reason: Int) {}
            })
        } catch (e: Exception) {}
    }
}

@Composable
fun AppNavigation(
    modifier: Modifier = Modifier,
    wifiDirectManager: WiFiDirectManager,
    fileTransferManager: FileTransferManager,
    bluetoothManager: MyBluetoothManager,
    btTransferEvents: kotlinx.coroutines.flow.SharedFlow<TransferEvent>
) {
    val navController = rememberNavController()
    val peers by wifiDirectManager.peers.collectAsState()
    val isWifiDirectEnabled by wifiDirectManager.isWifiP2pEnabled.collectAsState()
    val recentTransfers = remember { mutableStateListOf<String>() }

    LaunchedEffect(Unit) {
        fileTransferManager.transferEvents.collect { event ->
            if (event is TransferEvent.FileReceived) {
                val filename = java.io.File(event.path).name
                recentTransfers.add(0, "Received (WiFi): $filename")
            } else if (event is TransferEvent.FileSent) {
                recentTransfers.add(0, "Sent (WiFi): ${event.filename}")
            }
        }
    }
    
    LaunchedEffect(Unit) {
        btTransferEvents.collect { event ->
            if (event is TransferEvent.FileReceived) {
                val filename = java.io.File(event.path).name
                recentTransfers.add(0, "Received (BT): $filename")
            } else if (event is TransferEvent.FileSent) {
                recentTransfers.add(0, "Sent (BT): ${event.filename}")
            }
        }
    }

    NavHost(navController = navController, startDestination = "home", modifier = modifier) {
        composable("home") {
            var selectedProtocol by remember { mutableStateOf("wifi") } // "wifi" or "bluetooth"
            HomeScreen(
                peers = peers,
                recentTransfers = recentTransfers,
                isWifiDirectEnabled = isWifiDirectEnabled,
                selectedProtocol = selectedProtocol,
                onProtocolSelected = { selectedProtocol = it },
                onNavigateToTransfer = { navController.navigate("transfer/$selectedProtocol") },
                onNavigateToChat = { navController.navigate("chat/$selectedProtocol") },
                onNavigateToAbout = { navController.navigate("about") }
            )
        }
        composable("transfer/{protocol}") { backStackEntry ->
           val protocol = backStackEntry.arguments?.getString("protocol") ?: "wifi"
           if (protocol == "wifi") {
               WiFiDirectApp(
                    mode = "transfer",
                    wifiDirectManager = wifiDirectManager,
                    fileTransferManager = fileTransferManager,
                    onBack = { navController.popBackStack() }
                )
           } else {
               BluetoothApp(
                    mode = "transfer",
                    bluetoothManager = bluetoothManager,
                    transferEvents = btTransferEvents,
                    onBack = { navController.popBackStack() }
                )
           }
        }
        composable("chat/{protocol}") { backStackEntry ->
           val protocol = backStackEntry.arguments?.getString("protocol") ?: "wifi"
           if (protocol == "wifi") {
               WiFiDirectApp(
                    mode = "chat",
                    wifiDirectManager = wifiDirectManager,
                    fileTransferManager = fileTransferManager,
                    onBack = { navController.popBackStack() }
                )
           } else {
               BluetoothApp(
                    mode = "chat",
                    bluetoothManager = bluetoothManager,
                    transferEvents = btTransferEvents,
                    onBack = { navController.popBackStack() }
                )
           }
        }
        composable("about") {
            AboutScreen(onBack = { navController.popBackStack() })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    peers: List<android.net.wifi.p2p.WifiP2pDevice>,
    recentTransfers: List<String>,
    isWifiDirectEnabled: Boolean,
    selectedProtocol: String,
    onProtocolSelected: (String) -> Unit,
    onNavigateToTransfer: () -> Unit,
    onNavigateToChat: () -> Unit,
    onNavigateToAbout: () -> Unit
) {
    var showExitDialog by remember { mutableStateOf(false) }
    val activity = (LocalContext.current as? android.app.Activity)

    androidx.activity.compose.BackHandler(enabled = true) {
        showExitDialog = true
    }

    if (showExitDialog) {
        AlertDialog(
            onDismissRequest = { showExitDialog = false },
            title = { Text("Exit App") },
            text = { Text("Are you sure you want to exit P2P Connect?") },
            confirmButton = {
                TextButton(onClick = { activity?.finish() }) {
                    Text("Exit")
                }
            },
            dismissButton = {
                TextButton(onClick = { showExitDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("P2P Share", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onNavigateToAbout) {
                        Icon(Icons.Default.Menu, contentDescription = "Menu")
                    }
                },
                actions = {
                    Box(
                        modifier = Modifier
                            .padding(end = 16.dp)
                            .size(40.dp)
                            .background(com.udink.p2p.ui.theme.GradientStart, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Person, contentDescription = "Profile", tint = androidx.compose.ui.graphics.Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = androidx.compose.ui.graphics.Color.Transparent,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNavigateToTransfer,
                icon = { Icon(Icons.Default.Search, contentDescription = null) },
                text = { Text("Scan to Connect") },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = RoundedCornerShape(24.dp)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            item { Spacer(modifier = Modifier.height(8.dp)) }

            // Protocol Selection
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().height(48.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(24.dp)),
                ) {
                    Box(
                        modifier = Modifier.weight(1f).fillMaxHeight()
                            .background(if (selectedProtocol == "wifi") MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(24.dp))
                            .clickable { onProtocolSelected("wifi") },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Wi-Fi Direct", color = if (selectedProtocol == "wifi") MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
                    }
                    Box(
                        modifier = Modifier.weight(1f).fillMaxHeight()
                            .background(if (selectedProtocol == "bluetooth") MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(24.dp))
                            .clickable { onProtocolSelected("bluetooth") },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Bluetooth", color = if (selectedProtocol == "bluetooth") MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
                    }
                }
            }
            
            // Status Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp) // Subtle shadow by drawing border or relying on background diff
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("Current Status", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(if (isWifiDirectEnabled) "WiFi Direct: ON" else "WiFi Direct: OFF", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        }
                        // Circular Progress representing ON
                        Box(contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(
                                progress = { if (isWifiDirectEnabled) 1f else 0f },
                                modifier = Modifier.size(64.dp),
                                color = if (isWifiDirectEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                strokeWidth = 6.dp,
                                trackColor = MaterialTheme.colorScheme.primaryContainer,
                                strokeCap = androidx.compose.ui.graphics.StrokeCap.Round
                            )
                            if (isWifiDirectEnabled) {
                                Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            } else {
                                Icon(Icons.Default.Close, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
            
            // Big Buttons
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Card(
                        onClick = onNavigateToTransfer,
                        modifier = Modifier
                            .weight(1f)
                            .height(140.dp),
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Box(modifier = Modifier.fillMaxSize()
                            .background(androidx.compose.ui.graphics.Brush.linearGradient(
                                colors = listOf(com.udink.p2p.ui.theme.GradientStart, com.udink.p2p.ui.theme.GradientEnd)
                            ))
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(20.dp),
                                verticalArrangement = Arrangement.SpaceBetween
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .background(androidx.compose.ui.graphics.Color.White.copy(alpha = 0.2f), RoundedCornerShape(16.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.Send, contentDescription = null, tint = androidx.compose.ui.graphics.Color.White)
                                }
                                Text("Send File", style = MaterialTheme.typography.titleMedium, color = androidx.compose.ui.graphics.Color.White)
                            }
                        }
                    }
                    
                    Card(
                        onClick = onNavigateToChat,
                        modifier = Modifier
                            .weight(1f)
                            .height(140.dp),
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(20.dp),
                            verticalArrangement = Arrangement.SpaceBetween
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .background(androidx.compose.ui.graphics.Color.White, RoundedCornerShape(16.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Send, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            }
                            Text("Chat", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                    }
                }
            }
            
            // Nearby Devices
            item {
                Text("Nearby Devices", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(12.dp))
                if (peers.isEmpty()) {
                    Text("No devices found.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        items(peers.size) { index ->
                            val peer = peers[index]
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(
                                    modifier = Modifier
                                        .size(64.dp)
                                        .border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
                                        .padding(4.dp)
                                        .background(MaterialTheme.colorScheme.surface, CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.Person, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(peer.deviceName ?: "Unknown", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
            
            // Recent Transfers
            item {
                Text("Recent Transfers", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(12.dp))
                if (recentTransfers.isEmpty()) {
                    Text("No recent transfers.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(80.dp)) // padding for FAB
                } else {
                    recentTransfers.forEach { transferName ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 12.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(12.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(androidx.compose.material.icons.Icons.Default.Description, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                }
                                Spacer(modifier = Modifier.width(16.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(transferName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                    Text("Success", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = androidx.compose.ui.graphics.Color(0xFF4CAF50))
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(80.dp)) // padding for FAB
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeatureCard(
    title: String,
    description: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        shape = RoundedCornerShape(24.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(28.dp))
            }
            Spacer(modifier = Modifier.width(20.dp))
            Column {
                Text(text = title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("About", fontWeight = FontWeight.Medium) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(24.dp))
            Box(
                modifier = Modifier
                    .size(100.dp)
                    .background(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(id = R.drawable.ic_launcher_foreground),
                    contentDescription = "App Icon",
                    modifier = Modifier.size(72.dp)
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                "P2P Connect",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                "Our Team Portfolio",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(32.dp))
            
            Text(
                "App Topics: p2p, android, wifi-direct, bluetooth-chat, file-sharing",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))
            
            DeveloperCard(
                name = "UDIN-K",
                githubUrl = "https://github.com/UDIN-K/",
                topic = "Android Developer",
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/UDIN-K/"))
                    context.startActivity(intent)
                }
            )
            Spacer(modifier = Modifier.height(16.dp))
            DeveloperCard(
                name = "Duwiii-0",
                githubUrl = "https://github.com/Duwiii-0",
                topic = "Android Developer",
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Duwiii-0"))
                    context.startActivity(intent)
                }
            )
            Spacer(modifier = Modifier.height(16.dp))
            DeveloperCard(
                name = "Blip (Muhammad Irzaldi)",
                githubUrl = "https://github.com/muhammadirzaldialamsyahtik24-blip",
                topic = "Android Developer",
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/muhammadirzaldialamsyahtik24-blip"))
                    context.startActivity(intent)
                }
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/UDIN-K")) 
                    context.startActivity(intent)
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("View Project on GitHub")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeveloperCard(name: String, githubUrl: String, topic: String, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val strippedUrl = if (githubUrl.endsWith("/")) githubUrl.dropLast(1) else githubUrl
            coil.compose.AsyncImage(
                model = "$strippedUrl.png",
                contentDescription = null,
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape),
                contentScale = ContentScale.Crop,
                error = painterResource(id = R.drawable.ic_launcher_foreground)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(text = name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                Text(text = topic, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(text = "GitHub Profile", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

data class TransferState(
    val isActive: Boolean = false,
    val isSending: Boolean = false,
    val filename: String = "",
    val progress: Float = 0f,
    val speedBytesPerSec: Long = 0L,
    val etaSeconds: Long = 0L
)

@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class)
@Composable
fun WiFiDirectApp(
    modifier: Modifier = Modifier,
    mode: String,
    wifiDirectManager: WiFiDirectManager,
    fileTransferManager: FileTransferManager,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val isWifiEnabled by wifiDirectManager.isWifiP2pEnabled.collectAsState()
    val peers by wifiDirectManager.peers.collectAsState()
    val connectionInfo by wifiDirectManager.connectionInfo.collectAsState()
    val chatMessages = remember { mutableStateListOf<Pair<String, String>>() }
    var chatInput by remember { mutableStateOf("") }
    var transferState by remember { mutableStateOf(TransferState()) }

    LaunchedEffect(Unit) {
        disconnectStandardWifi(context)
    }

    LaunchedEffect(Unit) {
        fileTransferManager.transferEvents.collect { event ->
            if (event is TransferEvent.ChatReceived) {
                chatMessages.add(Pair("Peer", event.message))
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

    val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        listOf(
            Manifest.permission.NEARBY_WIFI_DEVICES,
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_AUDIO
        )
    } else {
        listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        )
    }

    val permissionsState = rememberMultiplePermissionsState(permissions = permissions)

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            val host = fileTransferManager.peerIp
            if (host != null) {
                (context as? ComponentActivity)?.lifecycleScope?.launch {
                    fileTransferManager.sendFile(it, host)
                }
            } else {
                Toast.makeText(context, "Recipient IP unknown. Make sure both devices are connected.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    LaunchedEffect(connectionInfo?.groupFormed) {
        if (connectionInfo?.groupFormed == true) {
            launch(kotlinx.coroutines.Dispatchers.IO) {
                fileTransferManager.startServer()
            }
        } else {
            fileTransferManager.stopServer()
            fileTransferManager.peerIp = null
        }
    }

    LaunchedEffect(connectionInfo) {
        if (connectionInfo?.groupFormed == true && connectionInfo?.isGroupOwner == false) {
            val goIp = connectionInfo?.groupOwnerAddress?.hostAddress
            if (goIp != null) {
                fileTransferManager.peerIp = goIp
                launch(kotlinx.coroutines.Dispatchers.IO) {
                    kotlinx.coroutines.delay(1000)
                    fileTransferManager.sendPing(goIp)
                }
            }
        }
    }

    val locationManager = remember { context.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager }
    var isLocationEnabled by remember { mutableStateOf(locationManager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) || locationManager.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)) }
    
    var showMyQR by remember { mutableStateOf(false) }
    val thisDevice by wifiDirectManager.thisDevice.collectAsState()
    var pendingConnectMac by remember { mutableStateOf<String?>(null) }
    
    LaunchedEffect(peers, pendingConnectMac) {
        if (pendingConnectMac != null) {
            val peer = peers.find { it.deviceAddress == pendingConnectMac }
            if (peer != null) {
                Toast.makeText(context, "Device discovered! Connecting...", Toast.LENGTH_SHORT).show()
                wifiDirectManager.connect(peer) { error ->
                    Toast.makeText(context, error, Toast.LENGTH_SHORT).show()
                }
                pendingConnectMac = null
            }
        }
    }

    val scanLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = com.journeyapps.barcodescanner.ScanContract(),
        onResult = { result ->
            if (result.contents != null) {
                val scannedMac = result.contents
                val peer = peers.find { it.deviceAddress == scannedMac }
                if (peer != null) {
                    wifiDirectManager.connect(peer) { error ->
                        Toast.makeText(context, error, Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(context, "Device not discovered yet, searching...", Toast.LENGTH_SHORT).show()
                    pendingConnectMac = scannedMac
                    wifiDirectManager.discoverPeers { error ->
                        Toast.makeText(context, error, Toast.LENGTH_SHORT).show()
                        pendingConnectMac = null
                    }
                }
            }
        }
    )
    
    DisposableEffect(Unit) {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                isLocationEnabled = locationManager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) || locationManager.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)
            }
        }
        val filter = IntentFilter(android.location.LocationManager.PROVIDERS_CHANGED_ACTION)
        context.registerReceiver(receiver, filter)
        onDispose { context.unregisterReceiver(receiver) }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { 
                    if (connectionInfo?.groupFormed == true && mode == "chat") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(modifier = Modifier.size(40.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Person, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text("Connected Peer", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
                                Text("Connected via WiFi", color = androidx.compose.ui.graphics.Color(0xFF4CAF50), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    } else if (connectionInfo?.groupFormed == true && mode == "transfer") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Transfer Process", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleLarge)
                        }
                    } else {
                        Text("Find Devices", fontWeight = FontWeight.Medium, style = MaterialTheme.typography.titleLarge)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (connectionInfo?.groupFormed != true && permissionsState.allPermissionsGranted) {
                        IconButton(onClick = { showMyQR = true }) {
                            Icon(androidx.compose.ui.res.painterResource(android.R.drawable.ic_dialog_info), contentDescription = "Show QR")
                        }
                        IconButton(onClick = { scanLauncher.launch(com.journeyapps.barcodescanner.ScanOptions()) }) {
                            Icon(androidx.compose.ui.res.painterResource(android.R.drawable.ic_menu_camera), contentDescription = "Scan QR")
                        }
                    } else if (connectionInfo?.groupFormed == true) {
                        IconButton(onClick = { 
                            wifiDirectManager.disconnect() 
                            Toast.makeText(context, "Disconnected", Toast.LENGTH_SHORT).show()
                        }) {
                            Icon(Icons.Default.Close, contentDescription = "Disconnect")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = androidx.compose.ui.graphics.Color.Transparent,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        },
        bottomBar = {
            if (connectionInfo?.groupFormed == true) {
                if (mode == "chat") {
                    Surface(
                        modifier = Modifier.padding(16.dp),
                        shape = RoundedCornerShape(32.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextField(
                                value = chatInput,
                                onValueChange = { chatInput = it },
                                modifier = Modifier.weight(1f),
                                placeholder = { Text("Message...") },
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                                    unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                                    focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                                    unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent
                                )
                            )
                            IconButton(onClick = {
                                if (chatInput.isNotBlank()) {
                                    val ip = fileTransferManager.peerIp
                                    if (ip != null) {
                                        val msg = chatInput
                                        chatMessages.add(Pair("Me", msg))
                                        chatInput = ""
                                        (context as? ComponentActivity)?.lifecycleScope?.launch {
                                            fileTransferManager.sendChat(msg, ip)
                                        }
                                    } else {
                                        Toast.makeText(context, "Recipient IP unknown", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }) {
                                Icon(Icons.Default.Send, contentDescription = "Send", tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                } else {
                    var showAppDialog by remember { mutableStateOf(false) }

                    if (showAppDialog) {
                        AppSelectionDialog(
                            onDismissRequest = { showAppDialog = false },
                            onAppSelected = { appInfo ->
                                showAppDialog = false
                                val goIp = fileTransferManager.peerIp
                                if (goIp != null) {
                                    (context as? ComponentActivity)?.lifecycleScope?.launch {
                                        val uri = Uri.fromFile(java.io.File(appInfo.sourceDir))
                                        fileTransferManager.sendFile(uri, goIp, overrideFilename = "${appInfo.name}.apk")
                                    }
                                } else {
                                    Toast.makeText(context, "Wait for connection to finalize.", Toast.LENGTH_SHORT).show()
                                }
                            }
                        )
                    }
                    Surface(
                        modifier = Modifier.padding(16.dp),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(32.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(4.dp)
                        ) {
                            Button(
                                onClick = { filePickerLauncher.launch("*/*") },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(56.dp),
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary
                                )
                            ) {
                                Text("Send Files", fontWeight = FontWeight.SemiBold)
                            }
                            TextButton(
                                onClick = { showAppDialog = true },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(56.dp),
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
                                colors = ButtonDefaults.textButtonColors(
                                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            ) {
                                Text("Send App", fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp)
        ) {
            if (!permissionsState.allPermissionsGranted) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "Permissions required",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "We need permission to find nearby devices.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Button(
                            onClick = { permissionsState.launchMultiplePermissionRequest() },
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
                            modifier = Modifier.height(48.dp)
                        ) {
                            Text("Grant Permissions")
                        }
                    }
                }
                return@Scaffold
            }

            if (!isLocationEnabled) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Text(
                        text = "Location is disabled. Please enable Location in settings to discover nearby devices.",
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }

            if (!isWifiEnabled) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp)
                ) {
                    Text(
                        text = "Wi-Fi is disconnected or disabled. Please enable it.",
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            if (transferState.isActive) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
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

            if (connectionInfo?.groupFormed != true) {
                val infiniteTransition = rememberInfiniteTransition(label = "pulse")
                
                val scale1 by infiniteTransition.animateFloat(
                    initialValue = 0.5f,
                    targetValue = 2f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(2000, easing = LinearOutSlowInEasing),
                        repeatMode = RepeatMode.Restart
                    ),
                    label = "scale1"
                )
                val alpha1 by infiniteTransition.animateFloat(
                    initialValue = 0.5f,
                    targetValue = 0f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(2000, easing = LinearOutSlowInEasing),
                        repeatMode = RepeatMode.Restart
                    ),
                    label = "alpha1"
                )

                val scale2 by infiniteTransition.animateFloat(
                    initialValue = 0.5f,
                    targetValue = 2f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(2000, easing = LinearOutSlowInEasing),
                        repeatMode = RepeatMode.Restart,
                        initialStartOffset = StartOffset(1000)
                    ),
                    label = "scale2"
                )
                val alpha2 by infiniteTransition.animateFloat(
                    initialValue = 0.5f,
                    targetValue = 0f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(2000, easing = LinearOutSlowInEasing),
                        repeatMode = RepeatMode.Restart,
                        initialStartOffset = StartOffset(1000)
                    ),
                    label = "alpha2"
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(128.dp)
                            .scale(scale1)
                            .border(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = alpha1), CircleShape)
                    )
                    Box(
                        modifier = Modifier
                            .size(128.dp)
                            .scale(scale2)
                            .border(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = alpha2), CircleShape)
                    )
                    Button(
                        onClick = {
                            wifiDirectManager.discoverPeers { error ->
                                Toast.makeText(context, error, Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier
                            .size(64.dp),
                        shape = RoundedCornerShape(24.dp),
                        contentPadding = PaddingValues(0.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    ) {
                        Icon(
                            Icons.Default.Wifi,
                            contentDescription = "Search",
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Searching for devices",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "Make sure the other device is visible",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                Spacer(modifier = Modifier.height(32.dp))
            }

            if (connectionInfo?.groupFormed == true && mode == "chat") {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 80.dp)
                ) {
                    items(chatMessages) { message ->
                        val isMe = message.first == "Me"
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = if (isMe) Arrangement.End else Arrangement.Start
                        ) {
                            Box(
                                modifier = Modifier
                                    .padding(4.dp)
                                    .widthIn(max = 280.dp)
                                    .background(
                                        brush = if (isMe) androidx.compose.ui.graphics.Brush.linearGradient(
                                            listOf(com.udink.p2p.ui.theme.GradientStart, com.udink.p2p.ui.theme.GradientEnd)
                                        ) else androidx.compose.ui.graphics.Brush.linearGradient(
                                            listOf(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.surfaceVariant)
                                        ),
                                        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = if (isMe) 16.dp else 0.dp, bottomEnd = if (isMe) 0.dp else 16.dp)
                                    )
                                    .padding(12.dp)
                            ) {
                                Text(
                                    text = message.second,
                                    color = if (isMe) androidx.compose.ui.graphics.Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            } else if (connectionInfo?.groupFormed == true && mode == "transfer") {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(modifier = Modifier.height(32.dp))
                    Box(contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            progress = { if (transferState.isActive) transferState.progress else 1f },
                            modifier = Modifier.size(160.dp),
                            color = MaterialTheme.colorScheme.primary,
                            strokeWidth = 8.dp,
                            trackColor = MaterialTheme.colorScheme.primaryContainer,
                            strokeCap = androidx.compose.ui.graphics.StrokeCap.Round
                        )
                        Box(
                            modifier = Modifier.size(100.dp).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                    Text(
                        text = if (transferState.isActive) (if (transferState.isSending) "Sending..." else "Receiving...") else "Connected",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (transferState.isActive) transferState.filename else "To peer device",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "${(transferState.progress * 100).toInt()}%",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    
                    Spacer(modifier = Modifier.height(48.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Speed", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(if (transferState.isActive) formatSpeed(transferState.speedBytesPerSec) else "-", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("ETA", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(if (transferState.isActive) formatEta(transferState.etaSeconds) else "-", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            } else {
                Text(
                    text = "NEARBY DEVICES",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 4.dp, bottom = 12.dp)
                )

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(peers) { peer ->
                        PeerItem(
                            peer = peer,
                            isConnected = connectionInfo?.groupFormed == true && peer.status == WifiP2pDevice.CONNECTED,
                            onClick = {
                                wifiDirectManager.connect(peer) { error ->
                                    Toast.makeText(context, error, Toast.LENGTH_SHORT).show()
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    if (showMyQR) {
        val mac = thisDevice?.deviceAddress ?: ""
        AlertDialog(
            onDismissRequest = { showMyQR = false },
            title = { Text("My Device QR") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    if (mac.isNotEmpty()) {
                        val bitmap = remember(mac) { generateQRCode(mac) }
                        if (bitmap != null) {
                            androidx.compose.foundation.Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = "QR Code",
                                modifier = Modifier.size(200.dp)
                            )
                        } else {
                            Text("Failed to generate QR Code")
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(mac, style = MaterialTheme.typography.bodyMedium)
                    } else {
                        Text("Device address not available yet. Ensure Wi-Fi is enabled.")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showMyQR = false }) { Text("Close") }
            }
        )
    }
}

fun generateQRCode(content: String): android.graphics.Bitmap? {
    try {
        val writer = com.google.zxing.qrcode.QRCodeWriter()
        val bitMatrix = writer.encode(content, com.google.zxing.BarcodeFormat.QR_CODE, 512, 512)
        val width = bitMatrix.width
        val height = bitMatrix.height
        val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.RGB_565)
        for (x in 0 until width) {
            for (y in 0 until height) {
                bitmap.setPixel(x, y, if (bitMatrix.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            }
        }
        return bitmap
    } catch (e: Exception) {
        return null
    }
}

fun formatSpeed(bytesPerSec: Long): String {
    if (bytesPerSec == 0L) return "---"
    val kb = bytesPerSec / 1024.0
    val mb = kb / 1024.0
    return if (mb >= 1.0) {
        String.format("%.1f MB/s", mb)
    } else {
        String.format("%.1f KB/s", kb)
    }
}

fun formatEta(seconds: Long): String {
    if (seconds == 0L) return "---"
    val mins = seconds / 60
    val secs = seconds % 60
    return if (mins > 0) {
        "${mins}m ${secs}s"
    } else {
        "${secs}s"
    }
}

@SuppressLint("MissingPermission")
@Composable
fun PeerItem(peer: WifiP2pDevice, isConnected: Boolean, onClick: () -> Unit) {
    val containerColor = if (isConnected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface
    val contentColor = if (isConnected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface
    val iconBgColor = if (isConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer
    val iconColor = if (isConnected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimaryContainer
    val border = if (!isConnected) androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline) else null

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = containerColor, contentColor = contentColor),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
        border = border,
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(
                        color = iconBgColor,
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Wifi,
                    contentDescription = null,
                    tint = iconColor,
                    modifier = Modifier.size(24.dp)
                )
            }
            
            Spacer(modifier = Modifier.width(16.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = peer.deviceName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                
                val statusText = when {
                    isConnected -> "Connected • Ready to receive"
                    peer.status == WifiP2pDevice.AVAILABLE -> "Available"
                    peer.status == WifiP2pDevice.INVITED -> "Connecting..."
                    peer.status == WifiP2pDevice.FAILED -> "Failed"
                    else -> "Tap to connect"
                }
                
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            if (isConnected) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(
                            color = MaterialTheme.colorScheme.primary,
                            shape = androidx.compose.foundation.shape.CircleShape
                        )
                )
            } else if (peer.status == WifiP2pDevice.INVITED) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            }
        }
    }
}

@Suppress("DEPRECATION")
fun disconnectStandardWifi(context: Context) {
    try {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
        wifiManager.disconnect()
    } catch (e: Exception) {
        // Ignored
    }
}
