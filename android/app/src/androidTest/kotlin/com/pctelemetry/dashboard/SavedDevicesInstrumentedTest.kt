package com.pctelemetry.dashboard

import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pctelemetry.core.ConnectionProfile
import com.pctelemetry.core.Protocol
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.SecretKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SavedDevicesInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val store get() = SecureProfileStore(context)
    private fun fixture(id: String) = ConnectionProfile("wss://127.0.0.1:47631/v1/stream",
        "A".repeat(64), id, "fixture-$id", Base64.encodeToString(ByteArray(32) { 7 }, Base64.NO_WRAP))
    @After fun clean() = runBlocking { store.clear() }

    @Test fun legacyCredentialsMigrateWithoutRePairing() = runBlocking {
        store.clear()
        val profile = fixture("legacy-device")
        // Create the Keystore alias, then reproduce the exact v1 on-disk format.
        store.save(profile)
        val key = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            .getKey("pc-telemetry-profile-v1", null) as SecretKey
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val encrypted = cipher.iv + cipher.doFinal(Protocol.json.encodeToString(profile).toByteArray())
        val marker = stringPreferencesKey("device-test-unrelated-setting")
        context.profileDataStore.edit {
            it.remove(stringPreferencesKey("encrypted_profiles_v2"))
            it[stringPreferencesKey("encrypted_profile")] = Base64.encodeToString(encrypted, Base64.NO_WRAP)
            it[marker] = "preserved"
        }
        assertEquals(profile, store.load())
        assertEquals(listOf(profile), store.list())
        val preferences = context.profileDataStore.data.first()
        assertNull(preferences[stringPreferencesKey("encrypted_profile")])
        assertNotNull(preferences[stringPreferencesKey("encrypted_profiles_v2")])
        assertFalse(preferences[stringPreferencesKey("encrypted_profiles_v2")]!!.contains(profile.token))
        store.clear()
        assertEquals("preserved", context.profileDataStore.data.first()[marker])
        context.profileDataStore.edit { it.remove(marker) }
        Unit
    }

    @Test fun multipleDevicesSelectionAndReplacementKeepOtherCredentials() = runBlocking {
        store.clear()
        val first = fixture("first").copy(displayName = "FIDROP-MECH")
        val second = fixture("second").copy(displayName = "Second PC")
        store.save(first); store.save(second)
        assertEquals(second, store.load())
        assertEquals(first, store.select(first.deviceId))
        assertEquals(first, store.load())
        val replacement = first.copy(credentialId = "replacement-credential",
            token = Base64.encodeToString(ByteArray(32) { 8 }, Base64.NO_WRAP))
        store.save(replacement)
        assertEquals(2, store.list().size)
        assertEquals(second, store.list().first { it.deviceId == second.deviceId })
        store.rename(first.deviceId, "Renamed PC")
        assertEquals(replacement.copy(displayName = "Renamed PC"), store.load())
        store.remove(first.deviceId)
        assertNull(store.load())
        assertEquals(listOf(second), store.list())
        assertEquals(second, store.select(second.deviceId))
    }
}
