package com.past9.phoneaos

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.past9.phoneaos.data.SettingsStore
import com.past9.phoneaos.web.WebSearchService
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WebSearchSettingsTest {
    private lateinit var settings: SettingsStore

    @Before fun setUp() {
        settings = SettingsStore(ApplicationProvider.getApplicationContext<Context>())
        WebSearchService.clearCache()
    }

    @Test fun keysAreTrimmedStoredAndCleared() {
        settings.setWebSearchKey("tavily", "  tvly-secret  ")
        assertEquals("tvly-secret", settings.webSearchKey("tavily"))
        assertTrue(settings.state.value.webSearchKeys.contains("tavily"))
        settings.setWebSearchKey("exa", "exa-secret")
        assertEquals(setOf("tavily", "exa"), settings.state.value.webSearchKeys)
        settings.setWebSearchKey("tavily", null)
        assertNull(settings.webSearchKey("tavily"))
        assertEquals(setOf("exa"), settings.state.value.webSearchKeys)
    }

    @Test fun modeProviderAndParallelAreRemembered() {
        assertEquals("questions", settings.state.value.webSearchMode)
        assertEquals("auto", settings.state.value.webSearchProvider)
        assertTrue(settings.state.value.webSearchParallel)

        settings.setWebSearchMode("always")
        settings.setWebSearchProvider("exa")
        settings.setWebSearchParallel(false)

        assertEquals("always", settings.state.value.webSearchMode)
        assertEquals("exa", settings.state.value.webSearchProvider)
        assertFalse(settings.state.value.webSearchParallel)

        // Poll the values as the UI does; they must come back after a fresh read.
        val again = SettingsStore(ApplicationProvider.getApplicationContext<Context>())
        assertEquals("always", again.state.value.webSearchMode)
        assertEquals("exa", again.state.value.webSearchProvider)
        assertFalse(again.state.value.webSearchParallel)
    }
}
