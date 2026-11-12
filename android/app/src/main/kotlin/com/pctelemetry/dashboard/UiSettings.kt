package com.pctelemetry.dashboard

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class UiSettings(val theme: DashboardTheme = DashboardTheme.SPORT, val language: String = "system",
    val night: String = "system", val pixelShift: Boolean = true, val rogBranding: Boolean = true,
    val microphoneMeter: Boolean = false, val microphoneSensitivity: Int = 50)

class UiSettingsStore(private val context: Context) {
    private val theme = stringPreferencesKey("theme")
    private val language = stringPreferencesKey("language")
    private val night = stringPreferencesKey("night")
    private val pixels = booleanPreferencesKey("pixel_shift")
    private val rogBranding = booleanPreferencesKey("rog_branding")
    private val microphone = booleanPreferencesKey("microphone_meter")
    private val sensitivity = intPreferencesKey("microphone_sensitivity")
    val settings: Flow<UiSettings> = context.profileDataStore.data.map { values ->
        UiSettings(
            DashboardTheme.entries.firstOrNull { it.name == values[theme] } ?: DashboardTheme.SPORT,
            values[language]?.takeIf { it in setOf("system", "zh", "en") } ?: "system",
            values[night]?.takeIf { it in setOf("system", "on", "off") } ?: "system",
            values[pixels] ?: true, values[rogBranding] ?: true, values[microphone] ?: false,
            (values[sensitivity] ?: 50).coerceIn(0, 100))
    }
    suspend fun save(value: UiSettings) {
        context.profileDataStore.edit {
            it[theme] = value.theme.name; it[language] = value.language
            it[night] = value.night; it[pixels] = value.pixelShift
            it[rogBranding] = value.rogBranding
            it[microphone] = value.microphoneMeter
            it[sensitivity] = value.microphoneSensitivity.coerceIn(0, 100)
        }
    }
}
