package it.unisalento.bleiot

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import it.unisalento.bleiot.ui.theme.BleNotificationTheme
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class SettingsActivity : ComponentActivity() {
    
    private var bleService: BleAndMqttService? = null
    private var serviceBound = false
    
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as BleAndMqttService.LocalBinder
            bleService = binder.getService()
            serviceBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            serviceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Bind to the service
        val serviceIntent = Intent(this, BleAndMqttService::class.java)
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
        
        setContent {
            BleNotificationTheme {
                SettingsScreenWithMenu(
                    onMainClick = {
                        startActivity(Intent(this@SettingsActivity, MainActivity::class.java))
                        finish()
                    },
                    onMqttSettingsSaved = {
                        bleService?.reloadMqttSettings()
                    }
                )
            }
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreenWithMenu(onMainClick: () -> Unit, onMqttSettingsSaved: () -> Unit = {}) {
    var showMenu by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
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
                                onMainClick()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Settings") },
                            onClick = { 
                                showMenu = false
                                // Already on settings screen
                            }
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        SettingsScreen(
            modifier = Modifier.padding(paddingValues),
            onMqttSettingsSaved = onMqttSettingsSaved
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(modifier: Modifier = Modifier, onMqttSettingsSaved: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val mqttSettings = remember { MqttSettings.getInstance(context) }
    val remoteConfigManager = remember { RemoteConfigManager.getInstance(context) }
    
    var mqttConfig by remember { mutableStateOf(mqttSettings.getMqttConfig()) }
    var serverText by remember { mutableStateOf(mqttConfig.server) }
    var portText by remember { mutableStateOf(mqttConfig.port.toString()) }
    var configUrlText by remember { mutableStateOf(mqttSettings.getDeviceConfigUrl()) }
    var showSuccessMessage by remember { mutableStateOf(false) }
    var showConfigMessage by remember { mutableStateOf("") }
    var isDownloadingConfig by remember { mutableStateOf(false) }
    var lastUpdateTime by remember { mutableStateOf(remoteConfigManager.getLastUpdateTime()) }
    // 1. Create the scroll state
    val scrollState = rememberScrollState()
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp).verticalScroll(scrollState),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Settings",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 32.dp)
            )
            
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "MQTT Configuration",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                    
                    OutlinedTextField(
                        value = serverText,
                        onValueChange = { serverText = it },
                        label = { Text("MQTT Server") },
                        placeholder = { Text("broker.hivemq.com") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                    )
                    
                    OutlinedTextField(
                        value = portText,
                        onValueChange = { portText = it },
                        label = { Text("MQTT Port") },
                        placeholder = { Text("1883") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp)
                    )
                    
                    Button(
                        onClick = {
                            val port = portText.toIntOrNull() ?: 1883
                            val newConfig = MqttConfig(serverText, port)
                            mqttSettings.saveMqttConfig(newConfig)
                            mqttConfig = newConfig
                            showSuccessMessage = true
                            onMqttSettingsSaved()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Save MQTT Settings")
                    }
                    
                    if (showSuccessMessage) {
                        LaunchedEffect(showSuccessMessage) {
                            kotlinx.coroutines.delay(2000)
                            showSuccessMessage = false
                        }
                        
                        Text(
                            text = "Settings saved successfully!",
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            }
            
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Device Configuration",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                    
                    OutlinedTextField(
                        value = configUrlText,
                        onValueChange = { configUrlText = it },
                        label = { Text("Config URL") },
                        placeholder = { Text("https://example.com/device-config.yaml") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                    )
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                mqttSettings.saveDeviceConfigUrl(configUrlText)
                                showConfigMessage = "Config URL saved!"
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Save URL")
                        }
                        
                        Button(
                            onClick = {
                                if (configUrlText.isNotBlank()) {
                                    isDownloadingConfig = true
                                    scope.launch {
                                        mqttSettings.saveDeviceConfigUrl(configUrlText)
                                        val result = remoteConfigManager.downloadAndSaveConfig(configUrlText)
                                        isDownloadingConfig = false
                                        result.fold(
                                            onSuccess = { message ->
                                                showConfigMessage = message
                                                lastUpdateTime = remoteConfigManager.getLastUpdateTime()
                                            },
                                            onFailure = { error ->
                                                showConfigMessage = "Error: ${error.message}"
                                            }
                                        )
                                    }
                                }
                            },
                            enabled = !isDownloadingConfig && configUrlText.isNotBlank(),
                            modifier = Modifier.weight(1f)
                        ) {
                            if (isDownloadingConfig) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Text("Refresh")
                            }
                        }
                    }
                    
                    if (showConfigMessage.isNotEmpty()) {
                        LaunchedEffect(showConfigMessage) {
                            kotlinx.coroutines.delay(3000)
                            showConfigMessage = ""
                        }
                        
                        Text(
                            text = showConfigMessage,
                            color = if (showConfigMessage.startsWith("Error")) 
                                MaterialTheme.colorScheme.error 
                            else 
                                MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                    
                    if (lastUpdateTime > 0) {
                        val dateFormat = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
                        Text(
                            text = "Last updated: ${dateFormat.format(Date(lastUpdateTime))}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            }
            
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Current Configuration",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                    
                    Text(
                        text = "Server: ${mqttConfig.server}",
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                    Text(
                        text = "Port: ${mqttConfig.port}",
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                    Text(
                        text = "Config URL: ${if (configUrlText.isBlank()) "Not set" else configUrlText}",
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun SettingsScreenPreview() {
    BleNotificationTheme {
        SettingsScreen()
    }
}