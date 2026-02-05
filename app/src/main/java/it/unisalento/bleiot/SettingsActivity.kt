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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import it.unisalento.bleiot.ui.theme.BleNotificationTheme
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import it.unisalento.bleiot.Experiment
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class SettingsActivity : ComponentActivity() {
    
    private var bleService: BleAndMqttService? = null
    private var serviceBound = false
    @Inject lateinit var remoteConfigManager: RemoteConfigManager
    @Inject lateinit var deviceConfigManager: DeviceConfigurationManager
    
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
                    },
                    remoteConfigManager = remoteConfigManager
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
fun SettingsScreenWithMenu(
    onMainClick: () -> Unit, 
    onMqttSettingsSaved: () -> Unit = {},
    remoteConfigManager: RemoteConfigManager
) {
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
            onMqttSettingsSaved = onMqttSettingsSaved,
            remoteConfigManager = remoteConfigManager
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier, 
    onMqttSettingsSaved: () -> Unit = {},
    remoteConfigManager: RemoteConfigManager
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val mqttSettings = remember { MqttSettings.getInstance(context) }
    val experimentSettings = remember { ExperimentSettings.getInstance(context) }
    val appSettings = remember { AppConfigurationSettings.getInstance(context) }
    val appConfig = remember { appSettings.getAppConfig() }
    
    var mqttConfig by remember { mutableStateOf(mqttSettings.getMqttConfig()) }
    var serverText by remember { mutableStateOf(mqttConfig.server) }
    var serverUser by remember { mutableStateOf(mqttConfig.user) }
    var serverPassword by remember { mutableStateOf(mqttConfig.password) }
    var portText by remember { mutableStateOf(mqttConfig.port.toString()) }
    var configUrlText by remember { mutableStateOf(mqttSettings.getDeviceConfigUrl()) }
    var configScanTime by remember { mutableStateOf(appConfig.scanTime.toString()) }
    var showSuccessMessage by remember { mutableStateOf(false) }
    var showConfigMessage by remember { mutableStateOf("") }
    var isDownloadingConfig by remember { mutableStateOf(false) }
    var lastUpdateTime by remember { mutableStateOf(remoteConfigManager.getLastUpdateTime()) }
    
    var experimentServerUrl by remember { mutableStateOf(experimentSettings.getExperimentServerUrl()) }
    var experiments by remember { mutableStateOf<List<Experiment>>(emptyList()) }
    var selectedExperiment by remember { mutableStateOf<Experiment?>(null) }
    var expanded by remember { mutableStateOf(false) }

    // Fetch experiments when the screen is first composed
    // LaunchedEffect(Unit) {
    //     scope.launch {
    //         try {
    //             val experiments = remoteConfigManager.getExperiments(experimentServerUrl)
    //             val selectedId = experimentSettings.getSelectedExperimentId()
    //             if (selectedId != "None") {
    //                 selectedExperiment = experiments.find { it.id == selectedId }
    //             }
    //         } catch (e: Exception) {
    //             // Handle error
    //         }
    //     }
    // }
    
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
                        text = "Experiment Configuration",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = experimentServerUrl,
                            onValueChange = { experimentServerUrl = it },
                            label = { Text("Experiment Server URL") },
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = {
                                experimentSettings.saveExperimentServerUrl(experimentServerUrl)
                                scope.launch {
                                    try {
                                        experiments = remoteConfigManager.getExperiments(experimentServerUrl)
                                    } catch (e: Exception) {
                                        // Handle error
                                        Log.e("SettingsScreen", "Error fetching experiments", e)
                                    }
                                }
                            },
                            modifier = Modifier.padding(start = 8.dp)
                        ) {
                            Text("Get")
                        }
                    }
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ExposedDropdownMenuBox(
                            expanded = expanded,
                            onExpandedChange = { expanded = !expanded },
                            modifier = Modifier.weight(1f)
                        ) {
                            OutlinedTextField(
                                value = selectedExperiment?.id ?: "",
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Select Experiment") },
                                trailingIcon = {
                                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                                },
                                modifier = Modifier.menuAnchor()
                            )
                            ExposedDropdownMenu(
                                expanded = expanded,
                                onDismissRequest = { expanded = false }
                            ) {
                                experiments.forEach { experiment ->
                                    DropdownMenuItem(
                                        text = { Text(experiment.id) },
                                        onClick = {
                                            selectedExperiment = experiment
                                            experimentSettings.saveSelectedExperimentId(experiment.id)
                                            expanded = false
                                        }
                                    )
                                }
                            }
                        }
                        Button(
                            onClick = {
                                scope.launch {
                                    selectedExperiment?.let {
                                        val config = remoteConfigManager.getExperimentConfig(experimentServerUrl, it.id)
                                        // TODO: Do something with the config
                                    }
                                }
                            },
                            enabled = selectedExperiment != null,
                            modifier = Modifier.padding(start = 8.dp)
                        ) {
                            Text("Get")
                        }
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

                    OutlinedTextField(
                        value = serverUser.toString(),
                        onValueChange = { serverUser = it },
                        label = { Text("MQTT User") },
                        placeholder = { Text("user") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                    )

                    OutlinedTextField(
                        value = serverPassword.toString(),
                        onValueChange = { serverPassword = it },
                        label = { Text("MQTT Password") },
                        placeholder = { Text("password") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                    )
                    
                    Button(
                        onClick = {
                            val port = portText.toIntOrNull() ?: 1883
                            val newConfig = MqttConfig(serverText, port, serverUser, serverPassword)
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
                    OutlinedTextField(
                        value = configScanTime,
                        onValueChange = { configScanTime = it },
                        label = { Text("Scan Time (sec)") },
                        placeholder = { Text("10") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                    )
                    Button(
                        onClick = {
                            val time = configScanTime.toIntOrNull() ?: 10
                            appSettings.saveAppConfig(BleScanConfig(scanTime = time))
                            showSuccessMessage = true
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Save Scan Time")
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

