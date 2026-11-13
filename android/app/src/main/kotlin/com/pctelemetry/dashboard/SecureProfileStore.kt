package com.pctelemetry.dashboard

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.pctelemetry.core.ConnectionProfile
import com.pctelemetry.core.Protocol
import com.pctelemetry.core.SavedConnections
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.encodeToString
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal val Context.profileDataStore by preferencesDataStore("connection")

class SecureProfileStore(private val context: Context) {
    private val entry = stringPreferencesKey("encrypted_profile")
    private val entries = stringPreferencesKey("encrypted_profiles_v2")
    companion object { private val gate = Mutex() }
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("pc-telemetry-profile-v1", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("pc-telemetry-profile-v1",
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }
    private fun decrypt(encoded: String): String {
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        require(bytes.size in 29..262_144)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        val plaintext = cipher.doFinal(bytes.copyOfRange(12, bytes.size))
        try {
            return plaintext.toString(Charsets.UTF_8)
        } finally { plaintext.fill(0) }
    }
    private fun encrypt(profiles: SavedConnections): String {
        profiles.validate()
        val plaintext = Protocol.json.encodeToString(profiles).toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        try {
            val encrypted = cipher.iv + cipher.doFinal(plaintext)
            return Base64.encodeToString(encrypted, Base64.NO_WRAP)
        } finally { plaintext.fill(0) }
    }
    private fun read(preferences: Preferences): SavedConnections {
        preferences[entries]?.let { return Protocol.json.decodeFromString<SavedConnections>(decrypt(it)).also { it.validate() } }
        val legacy = preferences[entry]?.let {
            Protocol.json.decodeFromString<ConnectionProfile>(decrypt(it)).also { it.validate() }
        }
        return if (legacy == null) SavedConnections() else SavedConnections(listOf(legacy), legacy.deviceId)
    }
    private suspend fun update(change: (SavedConnections) -> SavedConnections): SavedConnections = gate.withLock {
        var result = SavedConnections()
        context.profileDataStore.edit { preferences ->
            result = change(read(preferences)).also { it.validate() }
            preferences[entries] = encrypt(result)
            preferences.remove(entry)
        }
        result
    }
    suspend fun load(): ConnectionProfile? {
        val saved = update { it } // Atomic migration; keep the original credentials intact.
        return saved.profiles.firstOrNull { it.deviceId == saved.selectedDeviceId }
    }
    suspend fun list(): List<ConnectionProfile> = update { it }.profiles
    suspend fun save(profile: ConnectionProfile) {
        profile.validate()
        update { old -> SavedConnections(old.profiles.filterNot { it.deviceId == profile.deviceId } + profile, profile.deviceId) }
    }
    suspend fun select(deviceId: String): ConnectionProfile {
        val saved = update { old ->
            require(old.profiles.any { it.deviceId == deviceId })
            old.copy(selectedDeviceId = deviceId)
        }
        return saved.profiles.first { it.deviceId == deviceId }
    }
    suspend fun rename(deviceId: String, name: String) {
        update { old -> old.copy(profiles = old.profiles.map {
            if (it.deviceId == deviceId) it.copy(displayName = name) else it
        }) }
    }
    suspend fun remove(deviceId: String) {
        update { old -> SavedConnections(old.profiles.filterNot { it.deviceId == deviceId },
            old.selectedDeviceId.takeUnless { it == deviceId }) }
    }
    suspend fun clear() = gate.withLock {
        context.profileDataStore.edit { it.remove(entry); it.remove(entries) }
        Unit
    }
}
