package com.pctelemetry.dashboard

import android.content.Context
import android.media.AudioManager
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MicrophoneInstrumentedTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val audio get() = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    @After fun stopMeter(): Unit = runBlocking {
        // Teardown must not depend on a UI scope that the activity rule may already be disposing.
        rule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        val store = UiSettingsStore(context)
        store.save(store.settings.first().copy(microphoneMeter = false))
        rule.waitUntil(5000) { audio.activeRecordingConfigurations.isEmpty() }
    }

    private fun beginDemo(): DashboardViewModel {
        val model = rule.runOnUiThread {
            ViewModelProvider(rule.activity)[DashboardViewModel::class.java].also {
                it.updateSettings(UiSettings(DashboardTheme.SPORT, "en", "off", false))
            }
        }
        rule.onNodeWithText("Enter DEMO").performClick()
        rule.waitUntil(5000) { audio.activeRecordingConfigurations.isEmpty() }
        return model
    }

    @Test fun microphoneLifecycleSwitchAndSensitivity(): Unit = runBlocking {
        val model = beginDemo()
        rule.onNodeWithTag("sport-microphone-meter").assertDoesNotExist()
        rule.onNodeWithContentDescription("Settings").performClick()
        val needsPermission = context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        rule.onNodeWithTag("microphone-switch").performScrollTo().assertIsOff().performClick()
        if (needsPermission) respondToPermission(allow = true)
        rule.waitUntil(5000) {
            runCatching { model.settings.value.microphoneMeter &&
                rule.onAllNodesWithTag("settings-close").fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false)
        }
        rule.onNodeWithTag("settings-close").performScrollTo().performClick()
        rule.waitUntil(5000) {
            rule.onAllNodesWithTag("sport-microphone-meter").fetchSemanticsNodes().firstOrNull()
                ?.config?.get(SemanticsProperties.StateDescription) == "Microphone active" &&
                audio.activeRecordingConfigurations.isNotEmpty()
        }
        val sessions = audio.activeRecordingConfigurations.map { it.clientAudioSessionId }.toSet()
        println("MIC CHECK active recording session established")
        rule.onNodeWithContentDescription("Settings").performClick()
        rule.onNodeWithTag("microphone-sensitivity").performScrollTo()
            .performTouchInput { click(androidx.compose.ui.geometry.Offset(width * 0.2f, centerY)) }
        val sensitivity = rule.onNodeWithTag("microphone-sensitivity").fetchSemanticsNode()
            .config[SemanticsProperties.ProgressBarRangeInfo].current.toInt()
        assertTrue(sensitivity in 0..35)
        rule.waitUntil(5000) { model.settings.value.microphoneSensitivity == sensitivity }
        println("MIC CHECK sensitivity selected=$sensitivity stored=" + UiSettingsStore(context).settings.first().microphoneSensitivity)
        assertEquals(sensitivity, withTimeout(5000) {
            UiSettingsStore(context).settings.first { it.microphoneSensitivity == sensitivity }
        }.microphoneSensitivity)
        assertEquals(sessions, audio.activeRecordingConfigurations.map { it.clientAudioSessionId }.toSet())
        println("MIC CHECK sensitivity persisted without replacing recording session")
        rule.onNodeWithTag("microphone-switch").performScrollTo()
        val directory = File(context.getExternalFilesDir(null), "qa").apply { mkdirs() }
        File(directory, "microphone-settings.png").outputStream().use {
            rule.onNode(isDialog()).captureToImage().asAndroidBitmap()
                .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        rule.onNodeWithTag("settings-close").performScrollTo().performClick()
        save("sport-microphone.png")

        rule.onNodeWithContentDescription("SPORT dashboard").performTouchInput { swipeLeft() }
        rule.onNodeWithContentDescription("HUD dashboard").assertExists()
        rule.onNodeWithTag("sport-microphone-meter").assertDoesNotExist()
        rule.waitUntil(5000) { audio.activeRecordingConfigurations.isEmpty() }
        println("MIC CHECK HUD released microphone")
        rule.onNodeWithContentDescription("HUD dashboard").performTouchInput { swipeRight() }
        rule.waitUntil(5000) { audio.activeRecordingConfigurations.isNotEmpty() }

        rule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        rule.waitUntil(5000) { audio.activeRecordingConfigurations.isEmpty() }
        rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        rule.waitUntil(5000) { audio.activeRecordingConfigurations.isNotEmpty() }
        println("MIC CHECK background released and foreground resumed microphone")
        rule.onNodeWithContentDescription("Settings").performClick()
        rule.onNodeWithTag("microphone-switch").performScrollTo().assertIsOn().performClick()
        rule.waitUntil(5000) { audio.activeRecordingConfigurations.isEmpty() }
        rule.onNodeWithTag("microphone-switch").assertIsOff()
        rule.onNodeWithTag("settings-close").performScrollTo().performClick()
        rule.onNodeWithTag("sport-microphone-meter").assertDoesNotExist()
        println("MIC CHECK switch off: stored=" + UiSettingsStore(context).settings.first().microphoneMeter)
        assertFalse(withTimeout(5000) { UiSettingsStore(context).settings.first { !it.microphoneMeter } }.microphoneMeter)
        println("MIC CHECK disabled preference persisted")

        // Engine-off also releases an enabled microphone, independently of the saved preference.
        rule.runOnUiThread { model.changeSettings { it.copy(microphoneMeter = true) } }
        rule.waitUntil(5000) { audio.activeRecordingConfigurations.isNotEmpty() }
        rule.onNodeWithTag("engine-stop-control").performClick()
        rule.onNodeWithTag("engine-off-screen").assertExists()
        rule.waitUntil(5000) { audio.activeRecordingConfigurations.isEmpty() }
        println("MIC CHECK engine-off released microphone")
    }

    @Test fun permissionDeniedDoesNotEnableCapture() {
        val model = beginDemo()
        rule.onNodeWithContentDescription("Settings").performClick()
        rule.onNodeWithTag("microphone-switch").performScrollTo().performClick()
        respondToPermission(allow = false)
        rule.waitUntil(5000) {
            runCatching {
                !model.settings.value.microphoneMeter &&
                    rule.onAllNodesWithText("Microphone permission was not granted. Meters remain off.")
                        .fetchSemanticsNodes().isNotEmpty()
            }.getOrDefault(false)
        }
        rule.onNodeWithTag("microphone-switch").assertIsOff()
        rule.onNodeWithTag("sport-microphone-meter").assertDoesNotExist()
        assertTrue(audio.activeRecordingConfigurations.isEmpty())
    }

    private fun respondToPermission(allow: Boolean) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        rule.waitUntil(5000) {
            val owner = automation.rootInActiveWindow?.packageName?.toString() ?: ""
            owner.contains("permissioncontroller") || owner.contains("packageinstaller")
        }
        val root = automation.rootInActiveWindow!!
        val names = if (allow) listOf("permission_allow_button", "permission_allow_foreground_only_button")
            else listOf("permission_deny_button")
        val button = names.asSequence().flatMap {
            root.findAccessibilityNodeInfosByViewId("${root.packageName}:id/$it").asSequence()
        }.firstOrNull()
        assertNotNull("Android permission dialog must expose the requested action", button)
        assertTrue(button!!.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
    }

    @Test fun sensitivityReducesLoudSignalsWithoutInventingSilence() {
        val silence = ShortArray(800)
        val loudFixture = ShortArray(800) { Short.MAX_VALUE }
        val quietFixture = ShortArray(800) { 400 }
        assertEquals(0f, microphoneLevel(silence, silence.size, 100), 0.00001f)
        assertTrue(microphoneLevel(loudFixture, loudFixture.size, 10) < 0.6f)
        assertTrue(microphoneLevel(loudFixture, loudFixture.size, 50) > 0.99f)
        assertTrue(microphoneLevel(quietFixture, quietFixture.size, 90) > microphoneLevel(quietFixture, quietFixture.size, 50))
    }

    private fun save(name: String) {
        val directory = File(context.getExternalFilesDir(null), "qa").apply { mkdirs() }
        File(directory, name).outputStream().use {
            rule.onNodeWithContentDescription("SPORT dashboard").captureToImage().asAndroidBitmap()
                .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
