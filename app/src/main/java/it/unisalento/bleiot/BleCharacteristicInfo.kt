package it.unisalento.bleiot

import android.bluetooth.BluetoothGattCharacteristic

data class BleCharacteristicInfo(
    val uuid: String,
    val properties: Int,
    var isNotifying: Boolean = false
) {
    val canRead: Boolean
        get() = (properties and BluetoothGattCharacteristic.PROPERTY_READ) != 0
    val canWrite: Boolean
        get() = (properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
    val canNotify: Boolean
        get() = (properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE)) != 0
}
