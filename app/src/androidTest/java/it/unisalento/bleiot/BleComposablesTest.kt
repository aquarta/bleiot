package it.unisalento.bleiot

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import it.unisalento.bleiot.ui.theme.BleNotificationTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import android.bluetooth.BluetoothDevice
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.kotlin.times

@RunWith(AndroidJUnit4::class)
class BleComposablesTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun bleNotificationApp_showsNoDevicesFound_whenDeviceListIsEmpty() {
        val uiState = MutableStateFlow(BleUiState(devicesList = emptyList()))

        composeTestRule.setContent {
            BleNotificationTheme {
                BleNotificationApp(
                    uiState = uiState,
                    onScanButtonClick = { },
                    onDeviceClick = { },
                    onDisconnectClick = { },
                    viewModel = BleViewModel() // Dummy ViewModel
                )
            }
        }

        composeTestRule.onNodeWithText("No devices found").assertIsDisplayed()
    }

    @Test
    fun bleNotificationApp_showsDeviceList_whenDeviceListIsNotEmpty() {
        val mockDevice = mock(BluetoothDevice::class.java)
        val deviceInfo = BleDeviceInfo(
            name = "Test Device",
            address = "00:11:22:33:44:55",
            deviceT = BleDeviceInfoTrans(name = "Test Device", address = "00:11:22:33:44:55", device = mockDevice)
        )
        val uiState = MutableStateFlow(BleUiState(devicesList = listOf(deviceInfo)))

        composeTestRule.setContent {
            BleNotificationTheme {
                BleNotificationApp(
                    uiState = uiState,
                    onScanButtonClick = { },
                    onDeviceClick = { },
                    onDisconnectClick = { },
                    viewModel = BleViewModel() // Dummy ViewModel
                )
            }
        }

        composeTestRule.onNodeWithText("Test Device").assertIsDisplayed()
        composeTestRule.onNodeWithText("00:11:22:33:44:55").assertIsDisplayed()
    }

    @Test
    fun bleNotificationApp_showsConnectedState_whenDeviceIsConnected() {
        val mockDevice = mock(BluetoothDevice::class.java)
        val deviceAddress = "00:11:22:33:44:55"
        val deviceInfo = BleDeviceInfo(
            name = "Test Device",
            address = deviceAddress,
            deviceT = BleDeviceInfoTrans(name = "Test Device", address = deviceAddress, device = mockDevice)
        )
        val uiState = MutableStateFlow(
            BleUiState(
                devicesList = listOf(deviceInfo),
                connectedDeviceAddresses = setOf(deviceAddress)
            )
        )

        composeTestRule.setContent {
            BleNotificationTheme {
                BleNotificationApp(
                    uiState = uiState,
                    onScanButtonClick = { },
                    onDeviceClick = { },
                    onDisconnectClick = { },
                    viewModel = BleViewModel() // Dummy ViewModel
                )
            }
        }

        composeTestRule.onNodeWithText("Connected").assertIsDisplayed()
        composeTestRule.onNodeWithText("Disconnect").assertIsDisplayed()
    }

    @Test
    fun bleNotificationApp_showsScanningIndicator_whenScanning() {
        val uiState = MutableStateFlow(BleUiState(statusText = "Scanning"))

        composeTestRule.setContent {
            BleNotificationTheme {
                BleNotificationApp(
                    uiState = uiState,
                    onScanButtonClick = { },
                    onDeviceClick = { },
                    onDisconnectClick = { },
                    viewModel = BleViewModel() // Dummy ViewModel
                )
            }
        }

        composeTestRule.onNode(hasTestTag("CircularProgressIndicator"), useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun clickingDevice_triggersOnDeviceClickCallback() {
        val mockDevice = mock(BluetoothDevice::class.java)
        val onDeviceClick: (BluetoothDevice) -> Unit = mock()
        val deviceInfo = BleDeviceInfo(
            name = "Test Device",
            address = "00:11:22:33:44:55",
            deviceT = BleDeviceInfoTrans(name = "Test Device", address = "00:11:22:33:44:55", device = mockDevice)
        )
        val uiState = MutableStateFlow(BleUiState(devicesList = listOf(deviceInfo)))

        composeTestRule.setContent {
            BleNotificationTheme {
                BleNotificationApp(
                    uiState = uiState,
                    onScanButtonClick = { },
                    onDeviceClick = onDeviceClick,
                    onDisconnectClick = { },
                    viewModel = BleViewModel() // Dummy ViewModel
                )
            }
        }

        composeTestRule.onNodeWithText("Test Device").performClick()
        verify(onDeviceClick, times(1)).invoke(mockDevice)
    }

    @Test
    fun clickingDisconnect_triggersOnDisconnectClickCallback() {
        val mockDevice = mock(BluetoothDevice::class.java)
        val onDisconnectClick: (String) -> Unit = mock()
        val deviceAddress = "00:11:22:33:44:55"
        val deviceInfo = BleDeviceInfo(
            name = "Test Device",
            address = deviceAddress,
            deviceT = BleDeviceInfoTrans(name = "Test Device", address = deviceAddress, device = mockDevice)
        )
        val uiState = MutableStateFlow(
            BleUiState(
                devicesList = listOf(deviceInfo),
                connectedDeviceAddresses = setOf(deviceAddress)
            )
        )

        composeTestRule.setContent {
            BleNotificationTheme {
                BleNotificationApp(
                    uiState = uiState,
                    onScanButtonClick = { },
                    onDeviceClick = { },
                    onDisconnectClick = onDisconnectClick,
                    viewModel = BleViewModel() // Dummy ViewModel
                )
            }
        }

        composeTestRule.onNodeWithText("Disconnect").performClick()
        verify(onDisconnectClick, times(1)).invoke(deviceAddress)
    }
}
