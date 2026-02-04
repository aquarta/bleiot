package it.unisalento.bleiot

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import it.unisalento.bleiot.BleCharacteristicInfo
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.delay

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
                            txPhy = deviceInfo.txPhy,
                            rxPhy = deviceInfo.rxPhy,
                            supportedPhy = deviceInfo.supportedPhy,
                            onPhyClick = { txPhy, rxPhy, phyOptions ->
                                viewModel.setPreferredPhy(deviceInfo.address, txPhy, rxPhy, phyOptions)
                            },
                            onPriorityClick = { priority ->
                                viewModel.requestConnectionPriority(deviceInfo.address, priority)
                            },
                            onTagNameChange = { tagName ->
                                viewModel.setAppTagName(deviceInfo.address, tagName)
                            },
                            onNotificationChange = { charUuid, isChecked ->
                                viewModel.setCharacteristicNotification(deviceInfo.address, charUuid, isChecked)
                            },
                            onSubscriptionChange = { measureName, isChecked ->
                                viewModel.setWhiteboardSubscription(deviceInfo.address, measureName, isChecked)
                            },
                            rssi = deviceInfo.rssi
                        )
                    }
                } else if (state.statusText.contains("Scanning")) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp)
                                .testTag("CircularProgressIndicator"),
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



@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceListItem(
    deviceName: String,
    deviceAddress: String,
    deviceT: BleDeviceInfoTrans,
    isConnected: Boolean,
    onClick: () -> Unit,
    onDisconnectClick: () -> Unit,
    txPhy: String,
    rxPhy: String,
    supportedPhy: String,
    onPhyClick: (Int, Int, Int) -> Unit,
    onPriorityClick: (Int) -> Unit,
    onTagNameChange: (String) -> Unit,
    onNotificationChange: (String, Boolean) -> Unit,
    onSubscriptionChange: (String, Boolean) -> Unit,
    rssi: Int
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
                                text = "TX: $txPhy",
                                color = MaterialTheme.colorScheme.onSecondary,
                                fontSize = 10.sp
                            )
                        }
                        Badge(
                            modifier = Modifier.padding(end = 4.dp),
                            containerColor = MaterialTheme.colorScheme.secondary
                        ) {
                            Text(
                                text = "RX: $rxPhy",
                                color = MaterialTheme.colorScheme.onSecondary,
                                fontSize = 10.sp
                            )
                        }
                        Badge(
                            modifier = Modifier.padding(end = 4.dp),
                        ) {
                            Text(
                                text = "RSSI: $rssi",
                                fontSize = 10.sp
                            )
                        }
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                        text = deviceName,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
            }

            Spacer(modifier = Modifier.height(3.dp))

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
                    var textFieldValue by remember { mutableStateOf(deviceT.appTagName) }

                    // Update local state if the ViewModel's state changes from the outside
                    LaunchedEffect(deviceT.appTagName) {
                        if (textFieldValue != deviceT.appTagName) {
                            textFieldValue = deviceT.appTagName
                        }
                    }

                    // Debounce updates to the ViewModel
                    LaunchedEffect(textFieldValue) {
                        if (textFieldValue != deviceT.appTagName) {
                            delay(400L) // wait for 400ms of inactivity
                            onTagNameChange(textFieldValue)
                        }
                    }

                    TextField(
                        value = textFieldValue,
                        onValueChange = { textFieldValue = it },
                        label = { Text("APP_TAG_NAME", fontSize = 12.sp) },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {

                        CharListItem(
                            charInfos = deviceT.bleServices,
                            deviceAddress = deviceAddress,
                            onNotificationChange = onNotificationChange
                        )


                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Start,
                        verticalAlignment = Alignment.Bottom
                    ) {
                        WhiteBoardListItem(
                            measureInfos = deviceT.whiteboardServices,
                            deviceAddress = deviceAddress,
                            onSubscriptionChange = onSubscriptionChange
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
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Start,
                        verticalAlignment = Alignment.Bottom
                    ) {
                        Button(onClick = { onPriorityClick(BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER) }) {
                            Text("Low Power")
                        }
                        Button(onClick = { onPriorityClick(BluetoothGatt.CONNECTION_PRIORITY_BALANCED) }) {
                            Text("Balanced")
                        }
                        Button(onClick = { onPriorityClick(BluetoothGatt.CONNECTION_PRIORITY_HIGH) }) {
                            Text("High")
                        }
                    }
                }
            }
        }
    }

}

@Composable
fun CharListItem(
    charInfos: List<BleCharacteristicInfo>,
    deviceAddress: String,
    onNotificationChange: (String, Boolean) -> Unit
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier.padding(start = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.Start
    ) {
        for (charInfo in charInfos) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = charInfo.uuid, // Using uuid as the name for now
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f)
                )

                if (charInfo.canRead) {
                    Button(
                        onClick = {
                            val intent = Intent(context, BleAndMqttService::class.java).apply {
                                action = BleAndMqttService.ACTION_READ_CHAR
                                putExtra(BleAndMqttService.EXTRA_DEVICE_ADDRESS, deviceAddress)
                                putExtra(BleAndMqttService.EXTRA_CHARACTERISTIC_NAME, charInfo.uuid)
                            }
                            context.startService(intent)
                        },
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text("READ", fontSize = 10.sp)
                    }
                }

                if (charInfo.canWrite) {
//                    var textToWrite by remember { mutableStateOf("") }
//                    TextField(
//                        value = textToWrite,
//                        onValueChange = { textToWrite = it },
//                        label = { Text("Value to Write") },
//                        modifier = Modifier.height(50.dp)
//                    )
//                    Button(
//                        onClick = {
//                            val intent = Intent(context, BleAndMqttService::class.java).apply {
//                                action = BleAndMqttService.ACTION_WRITE_CHAR
//                                putExtra(BleAndMqttService.EXTRA_DEVICE_ADDRESS, deviceAddress)
//                                putExtra(BleAndMqttService.EXTRA_CHARACTERISTIC_NAME, charInfo.uuid)
//                                putExtra("EXTRA_CHARACTERISTIC_VALUE", textToWrite)
//                            }
//                            context.startService(intent)
//                        },
//                        modifier = Modifier.height(32.dp)
//                    ) {
//                        Text("WRITE", fontSize = 10.sp)
//                    }
                }

                if (charInfo.canNotify) {
                    Switch(
                        checked = charInfo.isNotifying,
                        onCheckedChange = { checked ->
                            onNotificationChange(charInfo.uuid, checked)
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun WhiteBoardListItem(
    measureInfos: List<WhiteboardMeasureInfo>,
    deviceAddress: String,
    onSubscriptionChange: (String, Boolean) -> Unit
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
            for (measureInfo in measureInfos) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = measureInfo.name)
                    Switch(
                        checked = measureInfo.isSubscribed,
                        onCheckedChange = { checked ->
                            onSubscriptionChange(measureInfo.name, checked)
                        }
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