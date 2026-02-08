package it.unisalento.bleiot

/**
 * Constants for MoveSense device GATT Sensor Protocol (GSP)
 * Reference: https://www.movesense.com/docs/esw/gatt_sensordata_protocol/
 */
object MoveSenseConstants {
    // Command identifier for GSP protocol
    const val MS_GSP_COMMAND_ID: Byte = 1

    // Sensor reference IDs
    const val MS_GSP_IMU_ID: Byte = 99     // IMU9 (Accelerometer, Gyroscope, Magnetometer)
    const val MS_GSP_ECG_ID: Byte = 100    // ECG
}
