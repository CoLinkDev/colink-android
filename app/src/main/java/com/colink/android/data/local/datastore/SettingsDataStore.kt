package com.colink.android.data.local.datastore

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.colink.android.BuildConfig
import com.colink.android.domain.model.AppSettings
import com.colink.android.domain.model.DeviceIdentity
import com.colink.android.domain.model.Session
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.coLinkDataStore by preferencesDataStore(name = "colink")

@Singleton
class SettingsDataStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val dataStore = context.coLinkDataStore

    val settings: Flow<AppSettings> =
        dataStore.data.map { preferences ->
            AppSettings(
                serverUrl = preferences[SERVER_URL] ?: BuildConfig.SERVER_BASE_URL,
                autoStartOnBoot = preferences[AUTO_START_ON_BOOT] ?: false,
                deviceName = preferences[SETTINGS_DEVICE_NAME] ?: "",
                language = preferences[LANGUAGE] ?: "system",
                enableClipboardSync = preferences[ENABLE_CLIPBOARD_SYNC] ?: true,
            )
        }

    val session: Flow<Session?> =
        dataStore.data.map { preferences ->
            val userId = preferences[SESSION_USER_ID]
            val accessToken = preferences[SESSION_ACCESS_TOKEN]
            val refreshToken = preferences[SESSION_REFRESH_TOKEN]
            val expiresAt = preferences[SESSION_ACCESS_EXPIRES_AT]
            val refreshAt = preferences[SESSION_ACCESS_REFRESH_AT]
            val email = preferences[SESSION_EMAIL]
            if (userId == null || accessToken == null || refreshToken == null || expiresAt == null) {
                null
            } else {
                Session(
                    userId = userId,
                    accessToken = accessToken,
                    refreshToken = refreshToken,
                    accessTokenExpiresAt = expiresAt,
                    accessTokenRefreshAt = refreshAt ?: (expiresAt - 60_000L),
                    username = preferences[SESSION_USERNAME],
                    email = email,
                )
            }
        }

    val deviceIdentity: Flow<DeviceIdentity?> =
        dataStore.data.map { preferences ->
            val userId = preferences[DEVICE_USER_ID]
            val deviceId = preferences[DEVICE_ID]
            val name = preferences[DEVICE_NAME]
            val type = preferences[DEVICE_TYPE]
            val publicKey = preferences[DEVICE_PUBLIC_KEY]
            val privateKey = preferences[DEVICE_PRIVATE_KEY]
            if (
                deviceId == null ||
                name == null ||
                type == null ||
                publicKey == null ||
                privateKey == null
            ) {
                null
            } else {
                DeviceIdentity(
                    userId = userId?.takeIf { it.isNotBlank() },
                    deviceId = deviceId,
                    name = name,
                    type = type,
                    publicKey = publicKey,
                    privateKey = privateKey,
                    cloudKeySyncPending = preferences[DEVICE_CLOUD_KEY_SYNC_PENDING] ?: false,
                )
            }
        }

    suspend fun currentSettings(): AppSettings = settings.first()

    suspend fun currentSession(): Session? = session.first()

    suspend fun currentDeviceIdentity(): DeviceIdentity? = deviceIdentity.first()

    suspend fun saveSettings(settings: AppSettings) {
        dataStore.edit { preferences ->
            preferences[SERVER_URL] = normalizeServerUrl(settings.serverUrl)
            preferences[AUTO_START_ON_BOOT] = settings.autoStartOnBoot
            preferences[SETTINGS_DEVICE_NAME] = settings.deviceName.trim()
            preferences[LANGUAGE] = settings.language
            preferences[ENABLE_CLIPBOARD_SYNC] = settings.enableClipboardSync
        }
    }

    suspend fun saveServerUrl(serverUrl: String) {
        dataStore.edit { preferences ->
            preferences[SERVER_URL] = normalizeServerUrl(serverUrl)
        }
    }

    suspend fun saveSession(session: Session) {
        dataStore.edit { preferences ->
            preferences[SESSION_USER_ID] = session.userId
            preferences[SESSION_ACCESS_TOKEN] = session.accessToken
            preferences[SESSION_REFRESH_TOKEN] = session.refreshToken
            preferences[SESSION_ACCESS_EXPIRES_AT] = session.accessTokenExpiresAt
            preferences[SESSION_ACCESS_REFRESH_AT] = session.accessTokenRefreshAt
            session.username?.let { preferences[SESSION_USERNAME] = it } ?: preferences.remove(SESSION_USERNAME)
            session.email?.let { preferences[SESSION_EMAIL] = it } ?: preferences.remove(SESSION_EMAIL)
        }
    }

    suspend fun clearSession() {
        dataStore.edit { preferences ->
            preferences.remove(SESSION_USER_ID)
            preferences.remove(SESSION_ACCESS_TOKEN)
            preferences.remove(SESSION_REFRESH_TOKEN)
            preferences.remove(SESSION_ACCESS_EXPIRES_AT)
            preferences.remove(SESSION_ACCESS_REFRESH_AT)
            preferences.remove(SESSION_USERNAME)
            preferences.remove(SESSION_EMAIL)
        }
    }

    suspend fun saveDeviceIdentity(identity: DeviceIdentity) {
        dataStore.edit { preferences ->
            identity.userId?.let { preferences[DEVICE_USER_ID] = it } ?: preferences.remove(DEVICE_USER_ID)
            preferences[DEVICE_ID] = identity.deviceId
            preferences.remove(DEVICE_SECRET)
            preferences[DEVICE_NAME] = identity.name
            preferences[DEVICE_TYPE] = identity.type
            preferences[DEVICE_PUBLIC_KEY] = identity.publicKey
            preferences[DEVICE_PRIVATE_KEY] = identity.privateKey
            preferences[DEVICE_CLOUD_KEY_SYNC_PENDING] = identity.cloudKeySyncPending
        }
    }

    suspend fun clearDeviceIdentity() {
        dataStore.edit { preferences ->
            preferences.remove(DEVICE_USER_ID)
            preferences.remove(DEVICE_ID)
            preferences.remove(DEVICE_SECRET)
            preferences.remove(DEVICE_NAME)
            preferences.remove(DEVICE_TYPE)
            preferences.remove(DEVICE_PUBLIC_KEY)
            preferences.remove(DEVICE_PRIVATE_KEY)
            preferences.remove(DEVICE_CLOUD_KEY_SYNC_PENDING)
        }
    }

    companion object {
        private val AUTO_START_ON_BOOT = booleanPreferencesKey("auto_start_on_boot")
        private val DEVICE_ID = stringPreferencesKey("device_id")
        private val DEVICE_CLOUD_KEY_SYNC_PENDING = booleanPreferencesKey("device_cloud_key_sync_pending")
        private val DEVICE_NAME = stringPreferencesKey("device_name")
        private val DEVICE_PRIVATE_KEY = stringPreferencesKey("device_private_key")
        private val DEVICE_PUBLIC_KEY = stringPreferencesKey("device_public_key")
        private val DEVICE_SECRET = stringPreferencesKey("device_secret")
        private val DEVICE_TYPE = stringPreferencesKey("device_type")
        private val DEVICE_USER_ID = stringPreferencesKey("device_user_id")
        private val SERVER_URL = stringPreferencesKey("server_url")
        private val SETTINGS_DEVICE_NAME = stringPreferencesKey("settings_device_name")
        private val SESSION_ACCESS_EXPIRES_AT = longPreferencesKey("session_access_expires_at")
        private val SESSION_ACCESS_REFRESH_AT = longPreferencesKey("session_access_refresh_at")
        private val SESSION_ACCESS_TOKEN = stringPreferencesKey("session_access_token")
        private val SESSION_REFRESH_TOKEN = stringPreferencesKey("session_refresh_token")
        private val SESSION_USER_ID = stringPreferencesKey("session_user_id")
        private val SESSION_USERNAME = stringPreferencesKey("session_username")
        private val SESSION_EMAIL = stringPreferencesKey("session_email")
        private val LANGUAGE = stringPreferencesKey("language")
        private val ENABLE_CLIPBOARD_SYNC = booleanPreferencesKey("enable_clipboard_sync")

        private fun normalizeServerUrl(serverUrl: String): String {
            val trimmed = serverUrl.trim().trimEnd('/').ifBlank {
                return BuildConfig.SERVER_BASE_URL
            }
            return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                trimmed
            } else {
                "http://$trimmed"
            }
        }
    }
}
