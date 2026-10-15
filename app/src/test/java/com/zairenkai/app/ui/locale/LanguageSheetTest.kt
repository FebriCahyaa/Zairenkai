// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
package com.zairenkai.app.ui.locale

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = android.app.Application::class)
class LanguageSheetTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun choosingIndonesianPersistsAfterTheSheetIsRemoved() {
        val context = rule.activity
        val prefs = context.getSharedPreferences("zk_locale", android.content.Context.MODE_PRIVATE)
        prefs.edit().clear().commit()

        var showSheet by mutableStateOf(true)
        rule.setContent {
            MaterialTheme {
                if (showSheet) LanguageSheet(onDismiss = { showSheet = false })
            }
        }

        rule.onNodeWithText("Bahasa Indonesia").performClick()
        rule.waitUntil(timeoutMillis = 5_000) { prefs.getString("tag", null) == "id" }
        assertEquals("id", prefs.getString("tag", null))
    }
}
