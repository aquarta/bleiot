package it.unisalento.bleiot

import android.bluetooth.BluetoothDevice
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.StateFlow

/**
 * Main screen with menu for navigation
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreenWithMenu(
    uiState: StateFlow<BleUiState>,
    onScanButtonClick: () -> Unit,
    onDeviceClick: (BluetoothDevice) -> Unit,
    onDisconnectClick: () -> Unit,
    onSettingsClick: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("BLE IoT") },
                actions = {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Menu")
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Main") },
                            onClick = { 
                                showMenu = false
                                // Already on main screen
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Settings") },
                            onClick = { 
                                showMenu = false
                                onSettingsClick()
                            }
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        BleNotificationApp(
            uiState = uiState,
            onScanButtonClick = onScanButtonClick,
            onDeviceClick = onDeviceClick,
            onDisconnectClick = onDisconnectClick,
            modifier = Modifier.padding(paddingValues)
        )
    }
}

/**
 * Main Composable for the BLE scanning application
 */
@Composable
fun BleNotificationApp(
    uiState: StateFlow<BleUiState>,
    onScanButtonClick: () -> Unit,
    onDeviceClick: (BluetoothDevice) -> Unit,
    onDisconnectClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by uiState.collectAsState()

    MaterialTheme {
        Surface(
            modifier = modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header
                item {
                    AppHeader()
                }

                // Scan button
                item {
                    ScanButton(
                        text = state.scanButtonText,
                        onClick = onScanButtonClick
                    )
                }

                // Device list header (if devices exist)
                if (state.devicesList.isNotEmpty()) {
                    item {
                        Text(
                            text = "Discovered Devices:",
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp)
                        )
                    }
                }

                // Device list items
                if (state.devicesList.isNotEmpty()) {
                    items(state.devicesList) { deviceInfo ->
                        val isConnected = deviceInfo.address == state.connectedDeviceAddress

                        DeviceListItem(
                            deviceName = deviceInfo.name,
                            deviceAddress = deviceInfo.address,
                            isConnected = isConnected,
                            onClick = {
                                if (!isConnected) onDeviceClick(deviceInfo.device)
                            },
                            onDisconnectClick = {
                                if (isConnected) onDisconnectClick()
                            }
                        )
                    }
                } else if (state.statusText.contains("Scanning")) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                } else {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No devices found",
                                modifier = Modifier.alpha(0.6f)
                            )
                        }
                    }
                }

                // Status and data section
                item {
                    StatusCard(
                        statusText = state.statusText,
                        dataText = state.dataText
                    )
                }
            }
        }
    }
}

@Composable
fun AppHeader() {
    Text(
        text = "BLE Device Scanner",
        fontSize = 20.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(vertical = 24.dp)
    )
}

@Composable
fun ScanButton(
    text: String,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = Modifier.padding(bottom = 16.dp)
    ) {
        Text(text = text)
    }
}

@Composable
fun DeviceListSection(
    devicesList: List<BleDeviceInfo>,
    isScanning: Boolean,
    connectedDeviceAddress: String?,
    onDeviceClick: (BluetoothDevice) -> Unit,
    onDisconnectClick: () -> Unit
) {
    if (devicesList.isNotEmpty()) {
        Text(
            text = "Discovered Devices:",
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
        ) {
            items(devicesList) { deviceInfo ->
                val isConnected = deviceInfo.address == connectedDeviceAddress

                DeviceListItem(
                    deviceName = deviceInfo.name,
                    deviceAddress = deviceInfo.address,
                    isConnected = isConnected,
                    onClick = {
                        if (!isConnected) onDeviceClick(deviceInfo.device)
                    },
                    onDisconnectClick = {
                        if (isConnected) onDisconnectClick()
                    }
                )
            }
        }
    } else if (isScanning) {
        Box(
            modifier = Modifier
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator()
        }
    } else {
        Box(
            modifier = Modifier
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "No devices found",
                modifier = Modifier.alpha(0.6f)
            )
        }
    }
}

@Composable
fun DeviceListItem(
    deviceName: String,
    deviceAddress: String,
    isConnected: Boolean,
    onClick: () -> Unit,
    onDisconnectClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = deviceName,
                    fontWeight = FontWeight.Bold
                )

                if (isConnected) {
                    Badge(
                        modifier = Modifier.padding(end = 4.dp),
                        containerColor = MaterialTheme.colorScheme.primary
                    ) {
                        Text(
                            text = "Connected",
                            color = MaterialTheme.colorScheme.onPrimary,
                            fontSize = 10.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = deviceAddress,
                    fontSize = 14.sp,
                    modifier = Modifier.alpha(0.7f)
                )

                if (isConnected) {
                    Button(
                        onClick = onDisconnectClick,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        ),
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .height(32.dp)
                    ) {
                        Text(
                            text = "Disconnect",
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun StatusCard(
    statusText: String,
    dataText: String
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp)
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Status:",
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(end = 8.dp)
                )
                Text(text = statusText)
            }

            if (dataText != "No data received") {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Data:",
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    Text(text = dataText)
                }
            }
        }
    }
}