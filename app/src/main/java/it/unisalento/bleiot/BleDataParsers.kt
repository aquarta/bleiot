package it.unisalento.bleiot

import java.nio.ByteBuffer
import java.nio.ByteOrder
import android.util.Log
import it.unisalento.bleiot.MoveSenseConstants.MS_GSP_IMU_ID
import it.unisalento.bleiot.MoveSenseConstants.MS_GSP_ECG_ID

/**
 * Collection of BLE data parsers for different device types and characteristics
 */
object BleDataParsers {
    private const val TAG = "BleDataParsers"

    // MoveSense packet type constants
    private const val PACKET_TYPE_RESP = 1
    private const val PACKET_TYPE_DATA = 2
    private const val PACKET_TYPE_DATA_PART2 = 3

    sealed class MoveSenseParseResult {
        data class Success(val data: Map<String, Any>) : MoveSenseParseResult()
        object Partial : MoveSenseParseResult()
        object Error : MoveSenseParseResult()
    }

    /**
     * Parses MoveSense Notifications
     */
    fun parseMoveSenseChar(data: ByteArray, isPart2: Boolean = false): MoveSenseParseResult {
        if (data.size < 2) return MoveSenseParseResult.Error
        
        val packetType = data[0].toInt() and 0xFF
        val reference = data[1].toInt() and 0xFF
        val rate = 200
        val imuRate = 104

        return when (packetType) {
            PACKET_TYPE_DATA -> {
                when (reference.toByte()) {
                    MS_GSP_ECG_ID -> MoveSenseParseResult.Success(parseECGData(data, rate) ?: return MoveSenseParseResult.Error)
                    MS_GSP_IMU_ID -> MoveSenseParseResult.Partial
                    //MS_GSP_HR_ID -> MoveSenseParseResult.Success(parseHRData(data) ?: return MoveSenseParseResult.Error)
                    else -> MoveSenseParseResult.Error
                }
            }
            PACKET_TYPE_DATA_PART2 -> MoveSenseParseResult.Partial // Should be handled by manager
            else -> MoveSenseParseResult.Error
        }
    }

    /**
     * Stateless parser for combined IMU9 data
     */
    fun parseCombinedIMU9(combinedData: ByteArray): Map<String, Any>? {
        return parseIMU9Data(combinedData, 104)
    }

    /**
     * Parses BLE Heart Rate Measurement characteristic data
     */
    fun parseHeartRateMeasurement(data: ByteArray): Map<String, Any>? {
        if (data.size < 2) return null
        try {
            val flags = data[0].toInt() and 0xFF
            val hrFormat16Bit = (flags and 0x01) != 0
            var offset = 1
            val heartRate = if (hrFormat16Bit) {
                if (data.size >= offset + 2) {
                    val hr = (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
                    offset += 2
                    hr
                } else 0
            } else {
                if (data.size >= offset + 1) {
                    val hr = data[offset].toInt() and 0xFF
                    offset += 1
                    hr
                } else 0
            }
            return mapOf("heartRate" to heartRate, "flags" to flags)
        } catch (e: Exception) {
            return null
        }
    }

    /**
     * Parses ST Battery structure data
     */
    fun parseSTBatteryStruct(data: ByteArray): Map<String, Any>? {
        if (data.size < 9) return null
        try {
            val timestamp = (data[0].toInt() and 0xFF) or ((data[1].toInt() and 0xFF) shl 8)
            val batteryLevel = ((data[2].toInt() and 0xFF) or ((data[3].toInt() and 0xFF) shl 8)) / 10.0
            val voltage = (data[4].toInt() and 0xFF) or ((data[5].toInt() and 0xFF) shl 8)
            val current = (data[6].toInt() and 0xFF) or ((data[7].toInt() and 0xFF) shl 8)
            val status = data[8].toInt() and 0xFF
            return mapOf(
                "sample_timestamp" to timestamp,
                "batteryLevel" to batteryLevel,
                "voltage" to voltage,
                "current" to current,
                "status" to status
            )
        } catch (e: Exception) {
            return null
        }
    }

//    private fun parseHRData(data: ByteArray): Map<String, Any>? {
//        if (data.size < 6) {
//            Log.w(TAG, "HR data too short: ${data.size} bytes")
//            return null
//        }
//        try {
//            // Extract timestamp (32-bit little-endian at offset 2)
//            val hr = getFloat32LE(data, 2)
//            val result = mapOf(
//                "type" to "HR",
//                "hr" to MS_GSP_HR_ID,
//            )
//
//            Log.i(TAG, "ER Data Parsed: ${result}")
//            return result
//        }catch (e: Exception) {
//            Log.e(TAG, "Error parsing HR data: ${e.message}")
//            return null
//        }
//
//    }

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
    private fun parseIMU9Data(data: ByteArray, imuRate: Int): Map<String, Any>? {
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
                    "sample_timestamp" to rowTimestamp,
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