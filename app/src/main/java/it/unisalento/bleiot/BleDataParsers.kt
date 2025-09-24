package it.unisalento.bleiot

import android.util.Log

/**
 * Collection of BLE data parsers for different device types and characteristics
 */
object BleDataParsers {
    private const val TAG = "BleDataParsers"

    // MoveSense packet type constants
    // https://www.movesense.com/docs/esw/gatt_sensordata_protocol/#subscribe
    private const val PACKET_TYPE_DATA = 2
    private const val PACKET_TYPE_DATA_PART2 = 3

    // State for ongoing multi-part data updates
    private var ongoingDataUpdate: ByteArray? = null

    /**
     * Parses BLE Heart Rate Measurement characteristic data according to
     * Bluetooth SIG specification for Heart Rate Service
     *
     * @param data Raw byte array from the characteristic
     * @return Map containing parsed heart rate data, or null if parsing fails
     */
    fun parseHeartRateMeasurement(data: ByteArray): Map<String, Any>? {
        if (data.size < 2) {
            Log.w(TAG, "Heart rate data too short: ${data.size} bytes")
            return null
        }

        try {
            val flags = data[0].toInt() and 0xFF

            // Parse flags according to BLE Heart Rate specification
            val hrFormat16Bit = (flags and 0x01) != 0  // Bit 0: HR Format (0=UINT8, 1=UINT16)
            val sensorContactSupported = (flags and 0x04) != 0  // Bit 2: Sensor Contact Supported
            val sensorContactDetected = (flags and 0x02) != 0   // Bit 1: Sensor Contact Detected
            val energyExpendedPresent = (flags and 0x08) != 0   // Bit 3: Energy Expended Present
            val rrIntervalPresent = (flags and 0x10) != 0       // Bit 4: RR-Interval Present

            var offset = 1

            // Parse heart rate value
            val heartRate = if (hrFormat16Bit) {
                // 16-bit heart rate value (little-endian)
                if (data.size >= offset + 2) {
                    val hr = (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
                    offset += 2
                    hr
                } else {
                    Log.w(TAG, "Insufficient data for 16-bit heart rate")
                    0
                }
            } else {
                // 8-bit heart rate value
                if (data.size >= offset + 1) {
                    val hr = data[offset].toInt() and 0xFF
                    offset += 1
                    hr
                } else {
                    Log.w(TAG, "Insufficient data for 8-bit heart rate")
                    0
                }
            }

            // Parse energy expended (if present)
            val energyExpended = if (energyExpendedPresent && data.size >= offset + 2) {
                val energy = (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
                offset += 2
                energy
            } else null

            // Parse RR intervals (if present)
            val rrIntervals = mutableListOf<Int>()
            if (rrIntervalPresent) {
                while (offset + 1 < data.size) {
                    val rrInterval = (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
                    rrIntervals.add(rrInterval)
                    offset += 2
                }
            }

            val result = mutableMapOf<String, Any>(
                "heartRate" to heartRate,
                "hrFormat16Bit" to hrFormat16Bit,
                "sensorContactSupported" to sensorContactSupported,
                "sensorContactDetected" to sensorContactDetected,
                "energyExpendedPresent" to energyExpendedPresent,
                "rrIntervalPresent" to rrIntervalPresent,
                "flags" to flags
            )

            energyExpended?.let { result["energyExpended"] = it }
            if (rrIntervals.isNotEmpty()) {
                result["rrIntervals"] = rrIntervals
            }

            Log.i(TAG, "Heart Rate Parsed: HR=$heartRate bpm, Contact=${if(sensorContactDetected) "detected" else "not detected"}")
            return result

        } catch (e: Exception) {
            Log.e(TAG, "Error parsing heart rate data: ${e.message}")
            return null
        }
    }

    /**
     * Parses ST Battery structure data
     *
     * @param data Raw byte array from the characteristic
     * @return Map containing parsed battery data, or null if parsing fails
     */
    fun parseSTBatteryStruct(data: ByteArray): Map<String, Any>? {
        if (data.size < 9) {
            Log.w(TAG, "ST Battery data too short: ${data.size} bytes, expected 9")
            return null
        }

        try {
            // Parse according to the BLE_BatteryUpdate struct format
            // STORE_LE_16(buff, (HAL_GetTick() / 10));
            val timestamp = (data[0].toInt() and 0xFF) or ((data[1].toInt() and 0xFF) shl 8)

            // STORE_LE_16(buff + 2, (BatteryLevel * 10U));
            val batteryLevelRaw = (data[2].toInt() and 0xFF) or ((data[3].toInt() and 0xFF) shl 8)
            val batteryLevel = batteryLevelRaw / 10.0

            // STORE_LE_16(buff + 4, (Voltage));
            val voltage = (data[4].toInt() and 0xFF) or ((data[5].toInt() and 0xFF) shl 8)

            // STORE_LE_16(buff + 6, (Current));
            val current = (data[6].toInt() and 0xFF) or ((data[7].toInt() and 0xFF) shl 8)

            // buff[8] = (uint8_t)Status;
            val status = data[8].toInt() and 0xFF

            val result = mapOf(
                "timestamp" to timestamp,
                "batteryLevel" to batteryLevel,
                "voltage" to voltage,
                "current" to current,
                "status" to status
            )

            Log.i(TAG, "ST Battery Parsed: Level=${batteryLevel}%, Voltage=${voltage}mV, Current=${current}mA")
            return result

        } catch (e: Exception) {
            Log.e(TAG, "Error parsing ST battery data: ${e.message}")
            return null
        }
    }

    /**
     * Parses MoveSense Notifications
     *
     * These data can be IMU or ECG measurements
     *
     * @param data Raw byte array from the characteristic
     * @return Map containing parsed sensor data, or null if parsing fails
     */
    fun parseMoveSenseChar(data: ByteArray): Map<String, Any>? {
        if (data.size < 2) {
            Log.w(TAG, "MoveSense Notifications too short: ${data.size} bytes: ${data.toString()}")
            return null
        }
        val rate=200;
        try {
            val packetType = data[0].toInt() and 0xFF
            val reference = data[1].toInt() and 0xFF

            Log.d(TAG, "MoveSense packet type: $packetType, reference: $reference")

            when (packetType) {
                PACKET_TYPE_DATA -> {
                    if (reference == 100) {
                        // ECG data (reference 100) fits in one packet
                        return parseECGData(data,rate)
                    } else {
                        // Store first part of the incoming data for multi-part packets
                        ongoingDataUpdate = data
                        Log.d(TAG, "Stored first part of multi-part data, reference: $reference")
                        return null // Wait for second part
                    }
                }
                PACKET_TYPE_DATA_PART2 -> {
                    // Combine with stored first part
                    val firstPart = ongoingDataUpdate
                    if (firstPart != null) {
                        // Create combined data (skip type_id + ref num of the data_part2)
                        val combinedData = firstPart + data.sliceArray(2 until data.size)
                        ongoingDataUpdate = null
                        return parseIMU9Data(combinedData)
                    } else {
                        Log.w(TAG, "Received PACKET_TYPE_DATA_PART2 without first part")
                        return null
                    }
                }
                else -> {
                    Log.w(TAG, "Unknown MoveSense packet type: $packetType")
                    return null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing MoveSense data: ${e.message}")
            ongoingDataUpdate = null // Reset state on error
            return null
        }
    }

    /**
     * Parses ECG data from MoveSense device
     */
    private fun parseECGData(data: ByteArray, rate: Int): Map<String, Any>? {
        if (data.size < 6 + 16 * 4) {
            Log.w(TAG, "ECG data too short: ${data.size} bytes")
            return null
        }

        try {
            // Extract timestamp (32-bit little-endian at offset 2)
            val timestamp = getUInt32LE(data, 2)

            val ecgSamples = mutableListOf<Map<String, Any>>()

            // ECG package starts with timestamp and then array of 16 samples
            for (i in 0 until 16) {
                // Interpolate timestamp within the data notification
                val rowTimestamp = timestamp + (i * 1000 / rate)

                // Sample scaling is 0.38 uV/sample, convert to mV
                val sampleOffset = 6 + i * 4
                val sampleRaw = getInt32LE(data, sampleOffset)
                val sampleMV = sampleRaw * 0.38 * 0.001

                ecgSamples.add(mapOf(
                    "sample_timestamp" to rowTimestamp,
                    "value_mV" to sampleMV,
                    "sample_index" to i
                ))
            }

            val result = mapOf(
                "type" to "ECG",
                "reference" to 100,
                "base_timestamp" to timestamp,
                "rate" to rate,
                "samples" to ecgSamples
            )

            Log.i(TAG, "ECG Data Parsed: ${ecgSamples.size} samples, timestamp: $timestamp")
            return result

        } catch (e: Exception) {
            Log.e(TAG, "Error parsing ECG data: ${e.message}")
            return null
        }
    }

    /**
     * Parses IMU9 data from MoveSense device
     */
    private fun parseIMU9Data(data: ByteArray): Map<String, Any>? {
        if (data.size < 6 + 8 * 3 * 3 * 4) { // 8 samples * 3 sensors * 3 axes * 4 bytes
            Log.w(TAG, "IMU9 data too short: ${data.size} bytes")
            return null
        }

        try {
            // Extract timestamp (32-bit little-endian at offset 2)
            val timestamp = getUInt32LE(data, 2)

            val imuSamples = mutableListOf<Map<String, Any>>()

            // IMU9 package starts with timestamp and then three arrays (len 8*4 bytes) of xyz's
            for (i in 0 until 8) {
                // Interpolate timestamp within the data notification
                val rowTimestamp = timestamp + (i * 1000 / 104)

                // Each "row" starts with offset calculation
                val offset = 6 + i * 3 * 4
                val skip = 3 * 8 * 4

                // Extract accelerometer, gyroscope, and magnetometer data
                val accX = getFloat32LE(data, offset)
                val accY = getFloat32LE(data, offset + 4)
                val accZ = getFloat32LE(data, offset + 8)

                val gyroX = getFloat32LE(data, offset + skip)
                val gyroY = getFloat32LE(data, offset + skip + 4)
                val gyroZ = getFloat32LE(data, offset + skip + 8)

                val magX = getFloat32LE(data, offset + 2 * skip)
                val magY = getFloat32LE(data, offset + 2 * skip + 4)
                val magZ = getFloat32LE(data, offset + 2 * skip + 8)

                imuSamples.add(mapOf(
                    "timestamp" to rowTimestamp,
                    "acc_x" to accX,
                    "acc_y" to accY,
                    "acc_z" to accZ,
                    "gyro_x" to gyroX,
                    "gyro_y" to gyroY,
                    "gyro_z" to gyroZ,
                    "mag_x" to magX,
                    "mag_y" to magY,
                    "mag_z" to magZ,
                    "sample_index" to i
                ))
            }

            val result = mapOf(
                "type" to "IMU9",
                "base_timestamp" to timestamp,
                "samples" to imuSamples
            )

            Log.i(TAG, "IMU9 Data Parsed: ${imuSamples.size} samples, timestamp: $timestamp")
            return result

        } catch (e: Exception) {
            Log.e(TAG, "Error parsing IMU9 data: ${e.message}")
            return null
        }
    }

    // Helper functions for data extraction
    private fun getUInt32LE(data: ByteArray, offset: Int): Long {
        return ((data[offset].toInt() and 0xFF) or
                ((data[offset + 1].toInt() and 0xFF) shl 8) or
                ((data[offset + 2].toInt() and 0xFF) shl 16) or
                ((data[offset + 3].toInt() and 0xFF) shl 24)).toLong() and 0xFFFFFFFFL
    }

    private fun getInt32LE(data: ByteArray, offset: Int): Int {
        return (data[offset].toInt() and 0xFF) or
                ((data[offset + 1].toInt() and 0xFF) shl 8) or
                ((data[offset + 2].toInt() and 0xFF) shl 16) or
                ((data[offset + 3].toInt() and 0xFF) shl 24)
    }

    private fun getFloat32LE(data: ByteArray, offset: Int): Float {
        val intValue = getInt32LE(data, offset)
        return Float.fromBits(intValue)
    }

    /**
     * Parses BLE Battery Level structure data
     *
     * @param data Raw byte array from the characteristic
     * @return Map containing parsed battery data, or null if parsing fails
     */
    fun parseBatteryLevel(data: ByteArray): Map<String, Any>? {
        if (data.size < 1) {
            Log.w(TAG, "Battery Level too short: ${data.size} bytes")
            return null
        }
        val batteryLevel = data[0].toInt()
        val result = mutableMapOf<String, Any>(
            "batteryLevel" to batteryLevel,   
        )
        Log.i(TAG, "Batter Level : ${result}")
        return result
    }

    /**
     * Parses custom temperature data
     *
     * @param data Raw byte array from the characteristic
     * @return Map containing parsed temperature data, or null if parsing fails
     */
    fun parseCustomTemperature(data: ByteArray): Map<String, Any>? {
        if (data.size < 6) {
            Log.w(TAG, "Temperature data too short: ${data.size} bytes, expected at least 6")
            return null
        }

        try {
            val tempValue = data.sliceArray(6 until data.size).foldIndexed(0) { index, acc, byte ->
                acc or ((byte.toInt() and 0xFF) shl (8 * index))
            }
            val temperature = (tempValue / 10).toDouble()

            val result = mapOf(
                "temperature" to temperature,
                "rawValue" to tempValue
            )

            Log.i(TAG, "Custom Temperature Parsed: ${temperature}°C")
            return result

        } catch (e: Exception) {
            Log.e(TAG, "Error parsing custom temperature data: ${e.message}")
            return null
        }
    }

    /**
     * Parses generic data types
     */
    fun parse4ByteInteger(data: ByteArray): Int? {
        if (data.size < 4) return null
        return data.sliceArray(0 until 4).foldIndexed(0) { index, acc, byte ->
            acc or ((byte.toInt() and 0xFF) shl (8 * index))
        }
    }

    fun parse4ByteDouble(data: ByteArray): Double? {
        return parse4ByteInteger(data)?.toDouble()
    }

    fun parse4ByteFloat(data: ByteArray): Float? {
        return parse4ByteInteger(data)?.toFloat()
    }
}