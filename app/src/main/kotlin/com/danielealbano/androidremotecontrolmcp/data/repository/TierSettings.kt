package com.danielealbano.androidremotecontrolmcp.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.danielealbano.androidremotecontrolmcp.data.model.AppTier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists how much of the app is shown ([AppTier]); unset reads as the simplest. The developer
 * tier stays offered once it was unlocked (the version line tapped seven times).
 */
@Singleton
class TierSettings
    @Inject
    constructor(
        private val dataStore: DataStore<Preferences>,
    ) {
        val tier: Flow<AppTier> = dataStore.data.map { prefs -> AppTier.of(prefs[TIER_KEY]) }

        val developerUnlocked: Flow<Boolean> = dataStore.data.map { prefs -> prefs[DEVELOPER_KEY] == true }

        suspend fun set(tier: AppTier) {
            dataStore.edit { prefs ->
                prefs[TIER_KEY] = tier.code
                if (tier == AppTier.DEVELOPER) prefs[DEVELOPER_KEY] = true
            }
        }

        private companion object {
            val TIER_KEY = stringPreferencesKey("app_tier")
            val DEVELOPER_KEY = booleanPreferencesKey("app_tier_developer_unlocked")
        }
    }
