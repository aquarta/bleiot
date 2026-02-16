package it.unisalento.bleiot.data

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.BatteryManager
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import it.unisalento.bleiot.CharacteristicInfo
import it.unisalento.bleiot.DeviceConfigurationManager
import it.unisalento.bleiot.mqtt.MqttManager
import it.unisalento.bleiot.repositories.BleRepository
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SensorDataManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val deviceConfigManager: DeviceConfigurationManager,
    private val mqttManager: MqttManager,
    private val repository: BleRepository
) {
    private val TAG = "SensorDataManager"
    private val ongoingDataUpdates = mutableMapOf<String, ByteArray>()

    fun processCharacteristicData(
        gatt: BluetoothGatt,
        value: ByteArray,
        serviceUuid: String,
        characteristicUuid: String
    ) {
        val deviceName = gatt.device.name ?: "Unknown"
        val address = gatt.device.address

        val configPair = deviceConfigManager.findServiceAndCharacteristic(
            deviceName, serviceUuid, characteristicUuid,gatt.device.address
        )

        if (configPair != null) {
            val (_, characteristicInfo) = configPair
            
            // Special handling for MoveSense multi-part data
            if (characteristicInfo.dataType == "movesense_read") {
                handleMoveSenseData(gatt, value, characteristicInfo)
                return
            }

            val parsedData = deviceConfigManager.parseCharacteristicData(characteristicInfo, value)
            if (parsedData != null) {
                publishParsedData(characteristicInfo.name, characteristicInfo.mqttTopic, parsedData, gatt)
            }
        } else {
            // Check for known characteristic by UUID only
            deviceConfigManager.findCharacteristicByUuid(characteristicUuid)?.let { knownChar ->
                val parsedData = deviceConfigManager.parseCharacteristicData(knownChar, value)
                if (parsedData != null) {
                    publishParsedData(knownChar.name, knownChar.mqttTopic, parsedData, gatt)
                }
            }
        }
    }

    private fun handleMoveSenseData(gatt: BluetoothGatt, value: ByteArray, info: CharacteristicInfo) {
        val address = gatt.device.address
        val packetType = value[0].toInt() and 0xFF
        
        when (packetType) {
            2 -> { // PACKET_TYPE_DATA
                val reference = value[1].toInt() and 0xFF
                if (reference.toByte() == it.unisalento.bleiot.MoveSenseConstants.MS_GSP_IMU_ID) {
                    ongoingDataUpdates[address] = value
                } else {
                    val result = it.unisalento.bleiot.BleDataParsers.parseMoveSenseChar(value)
                    if (result is it.unisalento.bleiot.BleDataParsers.MoveSenseParseResult.Success) {
                        publishParsedData(info.name, info.mqttTopic, result.data, gatt)
                    }
                }
            }
            3 -> { // PACKET_TYPE_DATA_PART2
                val firstPart = ongoingDataUpdates.remove(address)
                if (firstPart != null) {
                    val combined = firstPart + value.sliceArray(2 until value.size)
                    val parsed = it.unisalento.bleiot.BleDataParsers.parseCombinedIMU9(combined)
                    if (parsed != null) {
                        publishParsedData(info.name, info.mqttTopic, parsed, gatt)
                    }
                }
            }
        }
    }

    private fun publishParsedData(name: String, topic: String, data: Any, gatt: BluetoothGatt) {
        val enrichedData = enrichData(data, gatt)
        val jsonString = if (enrichedData is Map<*, *>) {
            JSONObject(enrichedData as Map<String, Any>).toString()
        } else {
            enrichedData.toString()
        }

        repository.updateData("$name: $enrichedData")
        mqttManager.publish(topic, jsonString)
    }

    private fun enrichData(parsedData: Any, gatt: BluetoothGatt): Any {
        if (parsedData !is Map<*, *>) return parsedData

        val address = gatt.device.address
        val deviceState = repository.scannedDevices.value[address]
        
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val bluetoothAdapter = bluetoothManager.adapter

        val mutableData = (parsedData as Map<String, Any>).toMutableMap()
        mutableData["deviceName"] = gatt.device.name ?: "Unknown"
        mutableData["deviceAddress"] = address
        mutableData["gatewayName"] = bluetoothAdapter?.name ?: "Unknown"
        mutableData["gatewayBattery"] = getBatteryLevel()
        
        deviceState?.let {
            mutableData["rssi"] = it.rssi
            mutableData["tx_phy"] = it.txPhy
            mutableData["rx_phy"] = it.rxPhy
            if (it.appTagName.isNotEmpty()) {
                mutableData["APP_TAG_NAME"] = it.appTagName
            }
        }
        
        return mutableData
    }

    private fun getBatteryLevel(): Int {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }
}