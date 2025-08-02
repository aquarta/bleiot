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
import androidx.compose.foundation.text.KeyboardOptions
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
    val mqttSettings = remember { MqttSettings.getInstance(context) }
    
    var mqttConfig by remember { mutableStateOf(mqttSettings.getMqttConfig()) }
    var serverText by remember { mutableStateOf(mqttConfig.server) }
    var portText by remember { mutableStateOf(mqttConfig.port.toString()) }
    var showSuccessMessage by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
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