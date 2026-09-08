package dev.injun.remotesync.ui.setup

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.injun.remotesync.sync.Protocol
import dev.injun.remotesync.theme.RemoteSyncTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProtocolPickerScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun selectingSmbReportsTheProtocol() {
        var selected: Protocol? = null
        composeTestRule.setContent {
            RemoteSyncTheme { ProtocolPickerScreen(onSelect = { selected = it }, onBack = {}) }
        }

        composeTestRule.onNodeWithText("SMB").performClick()

        assertEquals(Protocol.SMB, selected)
    }
}
