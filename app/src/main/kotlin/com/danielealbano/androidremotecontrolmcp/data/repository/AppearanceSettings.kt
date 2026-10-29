package com.danielealbano.androidremotecontrolmcp.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.danielealbano.androidremotecontrolmcp.data.model.Appearance
import com.danielealbano.androidremotecontrolmcp.data.model.TextSize
import com.danielealbano.androidremotecontrolmcp.data.model.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Persists how the app looks ([Appearance]); unset keys read as "follow the phone, standard text". */
@Singleton
class AppearanceSettings
    @Inject
    constructor(
        private val dataStore: DataStore<Preferences>,
    ) {
        val appearance: Flow<Appearance> =
            dataStore.data.map { prefs ->
                Appearance(ThemeMode.of(prefs[THEME_KEY]), TextSize.of(prefs[TEXT_SIZE_KEY]))
            }

        suspend fun update(appearance: Appearance) {
            dataStore.edit { prefs ->
                prefs[THEME_KEY] = appearance.theme.code
                prefs[TEXT_SIZE_KEY] = appearance.textSize.code
            }
        }

        private companion object {
            val THEME_KEY = stringPreferencesKey("appearance_theme")
            val TEXT_SIZE_KEY = stringPreferencesKey("appearance_text_size")
        }
    }
