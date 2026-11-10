package com.pctelemetry.dashboard

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
        save("sport-demo.png")
        rule.onNodeWithContentDescription("SPORT dashboard").performTouchInput { swipeLeft() }
        rule.onNodeWithContentDescription("HUD dashboard").assertExists()
        save("hud-demo.png")
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
