package it.unisalento.bleiot

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import it.unisalento.bleiot.ui.theme.BleNotificationTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.rule.GrantPermissionRule
import android.Manifest
import android.os.Build

@RunWith(AndroidJUnit4::class)
class MainScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @get:Rule
    val grantPermissionRule: GrantPermissionRule = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        GrantPermissionRule.grant(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT
        )
    } else {
        GrantPermissionRule.grant(
            Manifest.permission.ACCESS_FINE_LOCATION
        )
    }

    private lateinit var viewModel: BleViewModel

    @Before
    fun setUp() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        viewModel = BleViewModel()
        viewModel.initialize(appContext)

        composeTestRule.setContent {
            BleNotificationTheme {
                MainScreenWithMenu(
                    uiState = viewModel.uiState,
                    onScanButtonClick = { viewModel.onScanClicked() },
                    onDeviceClick = { },
                    onDisconnectClick = { },
                    onSettingsClick = { },
                    viewModel = viewModel
                )
            }
        }
    }

    @Test
    fun mainScreen_displaysTitle() {
        // Check that the title is displayed
        composeTestRule.onNodeWithText("BLE IoT").assertIsDisplayed()
    }

    @Test
    fun initialScreen_showsCorrectElements() {
        // Verify that the "Start Scan" button is displayed
        composeTestRule.onNodeWithText("Start Scan").assertIsDisplayed()

        // Verify that the "No devices found" text is displayed
        composeTestRule.onNodeWithText("No devices found").assertIsDisplayed()

        // Verify that the status text is "Not scanning"
        composeTestRule.onNodeWithText("Status:").assertIsDisplayed()
        composeTestRule.onNodeWithText("Not scanning").assertIsDisplayed()
    }

    @Test
    fun clickingScanButton_updatesUiToScanning() {
        // Click the "Start Scan" button
        composeTestRule.onNodeWithText("Start Scan").performClick()

        composeTestRule.waitForIdle()

        // Verify that the scan button text changes to "Stop Scan"
        composeTestRule.onNodeWithText("Stop Scan").assertIsDisplayed()

        // Verify that the status text changes to "Scanning..."
        composeTestRule.onNodeWithText("Scanning...").assertIsDisplayed()

        // Stop the scan to clean up
        composeTestRule.onNodeWithText("Stop Scan").performClick()
    }

    @Test
    fun scanStops_afterTimeout() {
        // Click the "Start Scan" button
        composeTestRule.onNodeWithText("Start Scan").performClick()
        composeTestRule.waitForIdle() // Make sure scan has started

        // Wait for more than the scan period
        Thread.sleep(11000)
        composeTestRule.waitForIdle() // Allow UI to update

        // Verify that the scan button text is "Start Scan"
        composeTestRule.onNodeWithText("Start Scan").assertIsDisplayed()
    }
}
