// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
package com.zairenkai.app.ui.locale

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList
import com.zairenkai.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class AppLocaleContextTest {
    private fun context(): Context = RuntimeEnvironment.getApplication<android.app.Application>()

    private fun reset(context: Context) {
        context.getSharedPreferences("zk_locale", Context.MODE_PRIVATE)
            .edit().clear().commit()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager::class.java)?.applicationLocales =
                LocaleList.getEmptyLocaleList()
        }
    }

    @Test
    @Config(sdk = [32])
    fun firstLaunchDefaultsToIndonesianAndLoadsIndonesianResources() {
        val context = context()
        reset(context)

        AppLocale.ensureDefaultLocale(context)

        assertEquals(AppLanguage.INDONESIAN, AppLocale.current(context))
        assertEquals("Bahasa", AppLocale.wrap(context).getString(R.string.setup_language))
    }

    @Test
    @Config(sdk = [35])
    fun frameworkAppLanguageOverridesStalePrivatePreference() {
        val context = context()
        reset(context)
        AppLocale.ensureDefaultLocale(context)
        assertTrue(context.getSharedPreferences("zk_locale", Context.MODE_PRIVATE)
            .getBoolean("framework_locale_synced", false))

        // Simulate an external change from Android Settings > App language.
        context.getSystemService(LocaleManager::class.java).applicationLocales =
            LocaleList.forLanguageTags("en-GB")

        assertEquals(AppLanguage.ENGLISH_UK, AppLocale.current(context))
        assertEquals("Language", AppLocale.wrap(context).getString(R.string.setup_language))

        // Empty framework locales after a successful sync means follow system,
        // not reapply the old Indonesian value stored for migration/fallback.
        context.getSystemService(LocaleManager::class.java).applicationLocales =
            LocaleList.getEmptyLocaleList()
        assertEquals(AppLanguage.SYSTEM, AppLocale.current(context))
    }
}
