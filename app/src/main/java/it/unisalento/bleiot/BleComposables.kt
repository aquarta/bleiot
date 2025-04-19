package it.unisalento.bleiot

import android.bluetooth.BluetoothDevice
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.StateFlow

/**
 * Main Composable for the BLE scanning application
 */
@Composable
fun BleNotificationApp(
    uiState: StateFlow<BleUiState>,
    onScanButtonClick: () -> Unit,
    onDeviceClick: (BluetoothDevice) -> Unit
) {
    val state by uiState.collectAsState()

    MaterialTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                AppHeader()

                ScanButton(
                    text = state.scanButtonText,
                    onClick = onScanButtonClick
                )

                // Device list section
                DeviceListSection(
                    devicesList = state.devicesList,
                    isScanning = state.statusText.contains("Scanning"),
                    onDeviceClick = onDeviceClick
                )

                // Status and data section
                StatusCard(
                    statusText = state.statusText,
                    dataText = state.dataText
                )
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
    onDeviceClick: (BluetoothDevice) -> Unit
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
                DeviceListItem(
                    deviceName = deviceInfo.name,
                    deviceAddress = deviceInfo.address,
                    onClick = { onDeviceClick(deviceInfo.device) }
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
    onClick: () -> Unit
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
            Text(
                text = deviceName,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = deviceAddress,
                fontSize = 14.sp,
                modifier = Modifier.alpha(0.7f)
            )
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