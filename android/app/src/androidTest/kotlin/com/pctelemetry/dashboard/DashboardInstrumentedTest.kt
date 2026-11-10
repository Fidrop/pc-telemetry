package com.pctelemetry.dashboard

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pctelemetry.core.ConnectionProfile
import com.pctelemetry.core.Protocol
import com.pctelemetry.core.PairingClient
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import com.pctelemetry.core.PinnedSocketFactory
import com.pctelemetry.core.AuthResult
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DashboardInstrumentedTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @After fun clearProfile() = runBlocking { SecureProfileStore(context).clear() }

    @Test fun demoThemesRenderAndSwitch() {
        rule.runOnUiThread {
            ViewModelProvider(rule.activity)[DashboardViewModel::class.java]
                .updateSettings(UiSettings(DashboardTheme.SPORT, "en", "off", false))
        }
        rule.onNodeWithText("Enter DEMO").performClick()
        rule.waitUntil(10_000) {
            rule.onAllNodesWithContentDescription("SPORT dashboard").fetchSemanticsNodes().isNotEmpty() &&
                rule.onAllNodesWithText("Enter DEMO").fetchSemanticsNodes().isEmpty()
        }
        rule.mainClock.advanceTimeBy(2000)
        if (InstrumentationRegistry.getArguments().getString("visualCapture") == "true") {
            val model = rule.runOnUiThread { ViewModelProvider(rule.activity)[DashboardViewModel::class.java] }
            rule.waitUntil(75_000) { model.state.value.history.size >= 58 }
        }
        save("sport-demo.png")
        rule.onNodeWithContentDescription("SPORT dashboard").performTouchInput { swipeLeft() }
        rule.onNodeWithContentDescription("HUD dashboard").assertExists()
        save("hud-demo.png")
    }

    @Test fun settingsPersistAndLanguageSwitches(): Unit = runBlocking {
        val model = rule.runOnUiThread {
            ViewModelProvider(rule.activity)[DashboardViewModel::class.java].also {
                it.updateSettings(UiSettings(DashboardTheme.SPORT, "en", "off", false))
            }
        }
        rule.onNodeWithText("Enter DEMO").performClick()
        rule.onNodeWithContentDescription("Settings").performClick()
        rule.onNodeWithText("HUD").performClick()
        rule.onNodeWithText("TELEMETRY").performScrollTo().performClick()
        if (InstrumentationRegistry.getArguments().getString("visualCapture") == "true") {
            rule.onNodeWithTag("settings-close").performScrollTo()
            saveDialog("settings-en.png")
        }
        rule.onNodeWithText("简体中文").performScrollTo().performClick()
        rule.waitUntil(10_000) {
            rule.onAllNodesWithText("设置").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("设置").assertExists()
        rule.onNodeWithText("开启").performScrollTo().performClick()
        rule.onNodeWithContentDescription("OLED 像素微移").performScrollTo().performClick()
        val expected = UiSettings(DashboardTheme.HUD, "zh", "on", true, false)
        try { rule.waitUntil(5000) { model.settings.value == expected } }
        catch (failure: Throwable) { throw AssertionError("Expected settings $expected, observed ${model.settings.value}", failure) }
        val restored = withTimeout(5000) { UiSettingsStore(context).settings.first { it == expected } }
        assertEquals(expected, restored)
        if (InstrumentationRegistry.getArguments().getString("visualCapture") == "true") {
            rule.onNodeWithTag("settings-close").performScrollTo()
            saveDialog("settings-zh.png")
        }
        rule.onNodeWithTag("settings-close").performScrollTo().performClick()
        rule.onNodeWithContentDescription("HUD dashboard").assertExists()
    }

    @Test fun phoneStatusReportsBatteryAndTicksInBothThemes() {
        assertEquals("00:00:00", formatPhoneTime(java.time.LocalTime.MIDNIGHT))
        assertEquals("13:04:09", formatPhoneTime(java.time.LocalTime.of(13, 4, 9)))
        assertEquals("23:59:59", formatPhoneTime(java.time.LocalTime.of(23, 59, 59)))
        fun fixtureBattery(level: Int, scale: Int, status: Int) = android.content.Intent().apply {
            putExtra(android.os.BatteryManager.EXTRA_LEVEL, level)
            putExtra(android.os.BatteryManager.EXTRA_SCALE, scale)
            putExtra(android.os.BatteryManager.EXTRA_STATUS, status)
        }
        assertNull(PhoneBattery.from(null).percent)
        assertNull(PhoneBattery.from(fixtureBattery(5, 0, 0)).percent)
        assertNull(PhoneBattery.from(fixtureBattery(101, 100, 0)).percent)
        assertEquals(0, PhoneBattery.from(fixtureBattery(0, 100, 0)).percent)
        assertEquals(50, PhoneBattery.from(fixtureBattery(100, 200, 0)).percent)
        assertTrue(PhoneBattery.from(fixtureBattery(50, 100, android.os.BatteryManager.BATTERY_STATUS_CHARGING)).charging)

        rule.runOnUiThread {
            ViewModelProvider(rule.activity)[DashboardViewModel::class.java]
                .updateSettings(UiSettings(DashboardTheme.SPORT, "en", "off", false))
        }
        rule.onNodeWithText("Enter DEMO").performClick()
        val clock = Regex("Phone time ([0-2][0-9]:[0-5][0-9]:[0-5][0-9]), battery (N/A|[0-9]{1,3}%)")
        fun status(theme: String) = rule.onNodeWithContentDescription("$theme dashboard")
            .fetchSemanticsNode().config[SemanticsProperties.StateDescription]
        rule.waitUntil(5000) { clock.find(status("SPORT")) != null }
        val firstTime = clock.find(status("SPORT"))!!.groupValues[1]
        rule.waitUntil(4000) { clock.find(status("SPORT"))?.groupValues?.get(1) != firstTime }
        val battery = context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        val percent = PhoneBattery.from(battery).percent
        if (percent != null) assertTrue(status("SPORT").contains("battery $percent%"))
        rule.onNodeWithContentDescription("SPORT dashboard").performTouchInput { swipeLeft() }
        rule.waitUntil(5000) { clock.find(status("HUD")) != null }
        if (percent != null) assertTrue(status("HUD").contains("battery $percent%"))
    }

    @Test fun sportEngineRequiresHoldAndDoesNotChangeHud() {
        val model = rule.runOnUiThread {
            ViewModelProvider(rule.activity)[DashboardViewModel::class.java].also {
                it.updateSettings(UiSettings(DashboardTheme.SPORT, "en", "off", false))
            }
        }
        rule.onNodeWithText("Enter DEMO").performClick()
        rule.onNodeWithTag("engine-stop-control").performClick()
        rule.onNodeWithTag("engine-off-screen").assertExists()
        rule.onNodeWithTag("engine-start-button").performClick()
        rule.onNodeWithTag("engine-off-screen").assertExists()

        holdButton(450)
        rule.onNodeWithTag("engine-off-screen").assertExists()
        rule.onAllNodes(isDialog()).assertCountEquals(0)
        val directory = File(context.getExternalFilesDir(null), "qa").apply { mkdirs() }
        File(directory, "engine-off.png").outputStream().use {
            rule.onNodeWithTag("engine-off-screen").captureToImage().asAndroidBitmap()
                .compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        rule.onNodeWithTag("engine-off-screen").performTouchInput { swipeLeft() }
        rule.onNodeWithContentDescription("HUD dashboard").assertExists()
        rule.onNodeWithTag("engine-start-button").assertDoesNotExist()
        assertTrue(model.state.value.demo)
        rule.onNodeWithContentDescription("HUD dashboard").performTouchInput { swipeRight() }
        rule.onNodeWithTag("engine-off-screen").assertExists()
        holdButton(700)
        rule.onNodeWithTag("engine-off-screen").assertExists()
        holdButton(1150)
        rule.onNodeWithContentDescription("SPORT dashboard").assertExists()
        rule.onNodeWithTag("engine-off-screen").assertDoesNotExist()
        assertTrue(model.state.value.demo)
        save("sport-engine-on.png")
    }

    @Test fun sportStartupSweepDoesNotOverwriteTelemetry() {
        val model = rule.runOnUiThread {
            ViewModelProvider(rule.activity)[DashboardViewModel::class.java].also {
                it.updateSettings(UiSettings(DashboardTheme.SPORT, "en", "off", false))
            }
        }
        rule.onNodeWithText("Enter DEMO").performClick()
        rule.onNodeWithTag("engine-stop-control").performClick()
        rule.onNodeWithTag("engine-off-screen").assertExists()
        rule.mainClock.autoAdvance = false
        try {
            holdButton(1150) // Actual Android gesture time; animation time remains controlled separately.
            rule.mainClock.advanceTimeBy(720)
            val description = rule.onNodeWithContentDescription("SPORT dashboard")
                .fetchSemanticsNode().config[SemanticsProperties.StateDescription]
            assertTrue(description.contains("STARTUP SELF TEST"))
            val actual = model.state.value.snapshot!!
            assertTrue(actual.metrics.cpuUsage.value!! < 80)
            assertEquals(52.0, actual.metrics.ram.percent!!, 0.001)
            assertEquals(40.0, actual.metrics.vram.percent!!, 0.001)
            save("sport-startup-80.png")
            rule.mainClock.advanceTimeBy(1600)
            assertFalse(rule.onNodeWithContentDescription("SPORT dashboard")
                .fetchSemanticsNode().config[SemanticsProperties.StateDescription].contains("STARTUP SELF TEST"))
            rule.onNodeWithTag("engine-off-screen").assertDoesNotExist()
            save("sport-engine-restored.png")
        } finally { rule.mainClock.autoAdvance = true }
    }

    private fun holdButton(milliseconds: Int) {
        val center = rule.onNodeWithTag("engine-start-button").fetchSemanticsNode().boundsInWindow.center
        val origin = IntArray(2)
        rule.runOnUiThread { rule.activity.window.decorView.getLocationOnScreen(origin) }
        val x = center.x.toInt() + origin[0]; val y = center.y.toInt() + origin[1]
        val command = "input touchscreen swipe $x $y $x $y $milliseconds"
        android.os.ParcelFileDescriptor.AutoCloseInputStream(
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command))
            .bufferedReader().use { it.readText() }
    }

    @Test fun realCollectorAndKeystoreOnAndroid() = runBlocking {
        val pairingText = InstrumentationRegistry.getArguments().getString("livePairingBase64")
        assumeTrue("Explicit live pairing fixture required", !pairingText.isNullOrBlank())
        val info = Protocol.json.parseToJsonElement(android.util.Base64.decode(pairingText,
            android.util.Base64.DEFAULT).toString(Charsets.UTF_8)).jsonObject
        val endpoint = info.getValue("endpoint").jsonPrimitive.content
        val expectedPin = info.getValue("fingerprint").jsonPrimitive.content
        val client = PairingClient()
        assertEquals(expectedPin, client.fingerprint(endpoint))
        val profile = client.pair(endpoint, expectedPin, info.getValue("code").jsonPrimitive.content)
        assertEquals(info.getValue("deviceId").jsonPrimitive.content, profile.deviceId)
        val model = rule.runOnUiThread {
            ViewModelProvider(rule.activity)[DashboardViewModel::class.java].also {
                it.updateSettings(UiSettings(DashboardTheme.SPORT, "en", "off", false))
                it.connect(profile)
            }
        }
        try {
            rule.waitUntil(30_000) { model.state.value.snapshot?.mode == "live" }
        } catch (failure: Throwable) {
            println("Connection diagnostic phase=" + model.state.value.phase +
                " problem=" + model.state.value.message + " storageError=" + model.error.value +
                " hasProfile=" + (model.profile.value != null))
            throw failure
        }
        val frame = model.state.value.snapshot!!
        assertEquals(profile.deviceId, frame.deviceId)
        assertNotNull(frame.metrics.ram.usedBytes)
        val restored = SecureProfileStore(context).load()
        assertEquals(profile, restored)
        val storage = File(context.filesDir, "datastore/connection.preferences_pb").readBytes()
        assertFalse(storage.toString(Charsets.ISO_8859_1).contains(profile.token))
        save("sport-live.png")
        rule.runOnUiThread { model.rePair() }
        rule.waitUntil(10_000) { model.profile.value == null }
        assertNull(SecureProfileStore(context).load())
        val revokedSocket = PinnedSocketFactory().open(profile)
        try {
            revokedSocket.send(buildJsonObject {
                put("type", "auth"); put("version", 1)
                put("credentialId", profile.credentialId); put("token", profile.token)
            }.toString())
            val reply = Protocol.json.decodeFromString<AuthResult>(revokedSocket.receive()!!)
            assertFalse(reply.success)
        } finally { revokedSocket.close() }
    }
    private fun saveDialog(name: String) {
        val directory = File(context.getExternalFilesDir(null), "qa").apply { mkdirs() }
        File(directory, name).outputStream().use {
            rule.onNode(isDialog()).captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
    private fun save(name: String) {
        val directory = File(context.getExternalFilesDir(null), "qa").apply { mkdirs() }
        File(directory, name).outputStream().use {
            val theme = if (name.startsWith("hud")) "HUD dashboard" else "SPORT dashboard"
            rule.onNodeWithContentDescription(theme).captureToImage().asAndroidBitmap()
                .compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
