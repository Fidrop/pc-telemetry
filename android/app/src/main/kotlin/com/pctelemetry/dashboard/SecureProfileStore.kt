package com.pctelemetry.dashboard

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.pctelemetry.core.ConnectionProfile
import com.pctelemetry.core.Protocol
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString

internal val Context.profileDataStore by preferencesDataStore("connection")

class SecureProfileStore(private val context: Context) {
    private val entry = stringPreferencesKey("encrypted_profile")
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
    suspend fun load(): ConnectionProfile? {
        val encoded = context.profileDataStore.data.first()[entry] ?: return null
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        require(bytes.size in 29..65_536)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        val plaintext = cipher.doFinal(bytes.copyOfRange(12, bytes.size))
        try {
            return Protocol.json.decodeFromString<ConnectionProfile>(plaintext.toString(Charsets.UTF_8))
                .also { it.validate() }
        } finally { plaintext.fill(0) }
    }
    suspend fun save(profile: ConnectionProfile) {
        profile.validate()
        val plaintext = Protocol.json.encodeToString(profile).toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        try {
            val encrypted = cipher.iv + cipher.doFinal(plaintext)
            context.profileDataStore.edit { it[entry] = Base64.encodeToString(encrypted, Base64.NO_WRAP) }
        } finally { plaintext.fill(0) }
    }
    suspend fun clear() { context.profileDataStore.edit { it.remove(entry) } }
}
