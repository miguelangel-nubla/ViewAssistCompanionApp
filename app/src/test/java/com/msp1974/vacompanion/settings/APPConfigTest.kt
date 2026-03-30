package com.msp1974.vacompanion.settings

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import androidx.preference.PreferenceManager
import com.google.android.gms.common.util.ClientLibraryUtils
import com.google.firebase.Firebase
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.crashlytics.crashlytics
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.junit4.MockKRule
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class APPConfigTest {

    @get:Rule
    val mockkRule = MockKRule(this)

    @MockK(relaxed = true)
    lateinit var context: Context

    @MockK(relaxed = true)
    lateinit var sharedPreferences: SharedPreferences

    @MockK(relaxed = true)
    lateinit var editor: SharedPreferences.Editor

    @MockK(relaxed = true)
    lateinit var crashlytics: FirebaseCrashlytics

    private lateinit var config: APPConfig

    @Before
    fun setUp() {
        every { context.applicationContext } returns context
        
        mockkStatic(PreferenceManager::class)
        every { PreferenceManager.getDefaultSharedPreferences(any()) } returns sharedPreferences
        
        every { sharedPreferences.edit() } returns editor
        every { sharedPreferences.registerOnSharedPreferenceChangeListener(any()) } returns Unit

        mockkStatic(ClientLibraryUtils::class)
        every { ClientLibraryUtils.getPackageInfo(any(), any()) } returns null

        mockkStatic(Settings.Secure::class)
        every { Settings.Secure.getString(any(), any()) } returns "mock-android-id"

        mockkStatic("com.google.firebase.crashlytics.FirebaseCrashlyticsKt")
        every { Firebase.crashlytics } returns crashlytics

        config = APPConfig(context)
    }

    @After
    fun tearDown() {
        unmockkStatic(PreferenceManager::class)
        unmockkStatic(ClientLibraryUtils::class)
        unmockkStatic(Settings.Secure::class)
        unmockkStatic("com.google.firebase.crashlytics.FirebaseCrashlyticsKt")
    }

    @Test
    fun `test processSettings parses json string correctly`() {
        val jsonSettings = """
            {
                "ha_port": 8080,
                "ha_url": "http://mock-url.com",
                "wake_word_engine": "porcupine",
                "mic_mute": true,
                "screen_brightness": 70,
                "noise_suppression_level": 80
            }
        """.trimIndent()

        config.processSettings(jsonSettings)

        assertTrue(config.initSettings)
        assertEquals(8080, config.homeAssistantHTTPPort)
        assertEquals("http://mock-url.com", config.homeAssistantURL)
        assertEquals("porcupine", config.wakeWordEngine)
        assertEquals(true, config.micMuted)
        assertEquals(0.7f, config.screenBrightness, 0.001f)
        assertEquals(80, config.noiseSuppressionLevel)
    }

    @Test
    fun `test processSettings updates specific float fields properly`() {
        val jsonSettings = """
            {
                "wake_word_threshold": 85,
                "bump_sensitivity": 5.0
            }
        """.trimIndent()

        config.processSettings(jsonSettings)

        assertEquals(0.85f, config.wakeWordThreshold, 0.001f)
        assertEquals(0.5f, config.bumpSensitivity, 0.001f)
    }
}
