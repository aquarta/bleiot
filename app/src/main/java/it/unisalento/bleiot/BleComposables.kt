package it.unisalento.bleiot

import android.bluetooth.BluetoothDevice
import android.content.Intent
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
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
    onDisconnectClick: (String) -> Unit,
    onSettingsClick: () -> Unit,
    viewModel: BleViewModel
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
            modifier = Modifier.padding(paddingValues),
            viewModel = viewModel
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
    onDisconnectClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BleViewModel
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
                        val isConnected = deviceInfo.address in state.connectedDeviceAddresses
                        if ( isConnected){
                            val i = 0
                        }

                        DeviceListItem(
                            deviceName = deviceInfo.name,
                            deviceAddress = deviceInfo.address,
                            isConnected = isConnected,
                            deviceT = deviceInfo.deviceT,
                            onClick = {
                                if (!isConnected) onDeviceClick(deviceInfo.deviceT.device)
                            },
                            onDisconnectClick = {
                                if (isConnected) onDisconnectClick(deviceInfo.address)
                            },
                            phy = deviceInfo.phy,
                            supportedPhy = deviceInfo.supportedPhy,
                            onPhyClick = { txPhy, rxPhy, phyOptions ->
                                viewModel.setPreferredPhy(deviceInfo.address, txPhy, rxPhy, phyOptions)
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
                    deviceT = deviceInfo.deviceT,
                    isConnected = isConnected,
                    onClick = {
                        if (!isConnected) onDeviceClick(deviceInfo.deviceT.device)
                    },
                    onDisconnectClick = {
                        if (isConnected) onDisconnectClick()
                    },
                    phy = deviceInfo.phy,
                    supportedPhy = deviceInfo.supportedPhy,
                    onPhyClick = { txPhy, rxPhy, phyOptions ->
                        // The viewModel is not available in this scope.
                        // This composable is not used, so this is just a placeholder.
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
    deviceT: BleDeviceInfoTrans,
    isConnected: Boolean,
    onClick: () -> Unit,
    onDisconnectClick: () -> Unit,
    phy: String,
    supportedPhy: String,
    onPhyClick: (Int, Int, Int) -> Unit
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
                    Row {
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
                        Badge(
                            modifier = Modifier.padding(end = 4.dp),
                            containerColor = MaterialTheme.colorScheme.secondary
                        ) {
                            Text(
                                text = phy,
                                color = MaterialTheme.colorScheme.onSecondary,
                                fontSize = 10.sp
                            )
                        }
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
            if (isConnected){
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {

                        CharListItem(
                            charNames = deviceT.bleServices,
                            deviceAddress = deviceAddress
                        )


                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Start,
                        verticalAlignment = Alignment.Bottom
                    ) {
                        WhiteBoardListItem(
                            measureNames = deviceT.whiteboardServices,
                            deviceAddress = deviceAddress
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Start,
                        verticalAlignment = Alignment.Bottom
                    ) {
                        supportedPhy.split(",").forEach { phy ->
                            Button(
                                onClick = {
                                    when (phy.trim()) {
                                        "1M" -> onPhyClick(BluetoothDevice.PHY_LE_1M_MASK, BluetoothDevice.PHY_LE_1M_MASK, 0)
                                        "2M" -> onPhyClick(BluetoothDevice.PHY_LE_2M_MASK, BluetoothDevice.PHY_LE_2M_MASK, 0)
                                        "Coded" -> onPhyClick(BluetoothDevice.PHY_LE_CODED_MASK, BluetoothDevice.PHY_LE_CODED_MASK, 0)
                                    }
                                },
                                modifier = Modifier.padding(end = 8.dp)
                            ) {
                                Text(text = phy)
                            }
                        }
                    }
                }
            }
        }
    }

}

@Composable
fun CharListItem(
    charNames: List<String>,
    deviceAddress: String
) {
    val context = LocalContext.current
    // 1. Use a Column to stack the rows vertically
    Column(
        modifier = Modifier.padding(start = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp), // Adds space between the service rows
        horizontalAlignment = Alignment.Start
    ) {
        // 2. Iterate through the services
        for (charName in charNames) {
            // 3. Create a separate Button (or Row) for EACH service
            Button(
                onClick = {
                        val intent = Intent(context, BleAndMqttService::class.java).apply {
                        // Use the specific ACTION you added to the Service for enabling notify
                        action = BleAndMqttService.ACTION_ENABLE_CHAR_NOTIFY
                        putExtra(BleAndMqttService.EXTRA_DEVICE_ADDRESS, deviceAddress)
                        putExtra(BleAndMqttService.EXTRA_CHARACTERISTIC_NAME, charName)
                    }
                    // Start the service with the intent created above
                    context.startService(intent)
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                ),
                modifier = Modifier.height(32.dp)
            ) {
                Text(
                    text = charName,
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
fun WhiteBoardListItem(
    measureNames: List<String>,
    deviceAddress: String,
    ) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth() // Changed from fillMaxHeight to fillMaxWidth for better list behavior
            .padding(8.dp)
            // ADDED: Light gray border with rounded corners
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant, // or Color.LightGray
                shape = MaterialTheme.shapes.medium
            )
            .padding(16.dp), // Inner padding so content doesn't touch the border
        horizontalArrangement = Arrangement.SpaceBetween, // Space between Label and Buttons
        verticalAlignment = Alignment.Top
    ){
        Text(
            text = "Whiteboard",
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 8.dp)
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp), // Adds space between buttons
            horizontalAlignment = Alignment.End
        ) {
            for (measureName in measureNames) {
                Button(
                    onClick = {
                        val intent = Intent(context, BleAndMqttService::class.java).apply {
                            // Use the specific ACTION you added to the Service for enabling notify
                            action = BleAndMqttService.ACTION_ENABLE_WHITEBOARD_SUBSCRIBE
                            putExtra(BleAndMqttService.EXTRA_DEVICE_ADDRESS, deviceAddress)
                            putExtra(BleAndMqttService.EXTRA_WHITEBOARD_MEASURE, measureName)
                        }
                        // Start the service with the intent created above
                        context.startService(intent)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    ),
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .height(32.dp)
                ) {
                    Text(
                        text = measureName.toString(),
                        fontSize = 12.sp
                    )
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