package com.furkandurmaz.nsosyal.network

import android.content.SharedPreferences
import androidx.core.content.edit

class SessionStore(private val preferences: SharedPreferences) {
    val accessToken: String? get() = preferences.getString("backend_access_token", null)
    val refreshToken: String? get() = preferences.getString("backend_refresh_token", null)
    val mockAuthenticated: Boolean get() = preferences.getBoolean("mock_authenticated", false)
    val personalizationEnabled: Boolean get() = preferences.getBoolean("personalization_enabled", true)

    fun save(tokens: BackendTokens) {
        preferences.edit {
            putString("backend_access_token", tokens.accessToken)
            putString("backend_refresh_token", tokens.refreshToken)
            remove("mock_authenticated")
        }
    }

    fun saveMockAuthentication() {
        preferences.edit { putBoolean("mock_authenticated", true) }
    }

    fun savePersonalizationEnabled(enabled: Boolean) {
        preferences.edit { putBoolean("personalization_enabled", enabled) }
    }

    fun clear() {
        preferences.edit {
            remove("backend_access_token")
            remove("backend_refresh_token")
            remove("mock_authenticated")
            remove("authenticated")
        }
    }
}
