package com.ranorac.tjtimetable.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * A separate store from `tj_settings` on purpose: this one is **excluded from
 * cloud backup** (see `res/xml/backup_rules.xml`), because it holds the
 * 统一身份认证 token and the app's `client_secret`.
 */
private val Context.credentialsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "tj_credentials",
)

/**
 * Authorisation state for 同济大学开放平台.
 *
 * The platform exposes two OAuth2 modes and this app supports both, because
 * neither is universally usable:
 *
 *  - **客户端模式** needs a `client_id` / `client_secret` issued to a registered
 *    application. It is what a published app would ship.
 *  - **授权码模式** needs only a `client_id` and a registered redirect URI, and
 *    the student authorises with their own 统一身份认证 login.
 *
 * Because the app cannot register itself on the 开放平台, both the client id and
 * (for client mode) the secret are student-supplied in settings. Shipping a
 * baked-in secret would be worse than asking: a secret inside an APK is public.
 */
data class Credentials(
    val clientId: String = "",
    val clientSecret: String = "",
    val accessToken: String? = null,
    /** Unix seconds; 0 when never obtained. */
    val expiresAtEpochSec: Long = 0,
    /** 学号. */
    val userId: String = "",
    val userName: String? = null,
) {
    val hasClient: Boolean get() = clientId.isNotBlank()

    val hasToken: Boolean get() = !accessToken.isNullOrBlank()

    /**
     * Tokens last 7200s per the platform docs. A 60s skew keeps a request from
     * being sent with a token that expires mid-flight.
     */
    fun isTokenFresh(nowEpochSec: Long = System.currentTimeMillis() / 1000): Boolean =
        hasToken && nowEpochSec < expiresAtEpochSec - 60

    /** Client-credentials mode is usable when a secret was supplied too. */
    val canUseClientCredentials: Boolean get() = hasClient && clientSecret.isNotBlank()

    /** 授权码模式 only needs the client id; the login happens in a browser. */
    val canUseAuthorizationCode: Boolean get() = hasClient
}

class CredentialsStore(private val context: Context) {

    val credentials: Flow<Credentials> = context.credentialsDataStore.data.map { prefs ->
        Credentials(
            clientId = prefs[KEY_CLIENT_ID].orEmpty(),
            clientSecret = prefs[KEY_CLIENT_SECRET].orEmpty(),
            accessToken = prefs[KEY_ACCESS_TOKEN],
            expiresAtEpochSec = prefs[KEY_EXPIRES_AT] ?: 0L,
            userId = prefs[KEY_USER_ID].orEmpty(),
            userName = prefs[KEY_USER_NAME],
        )
    }

    suspend fun current(): Credentials = credentials.first()

    suspend fun setClient(clientId: String, clientSecret: String) = context.credentialsDataStore.edit {
        it[KEY_CLIENT_ID] = clientId.trim()
        it[KEY_CLIENT_SECRET] = clientSecret.trim()
    }

    suspend fun setStudent(userId: String, userName: String?) = context.credentialsDataStore.edit {
        it[KEY_USER_ID] = userId.trim()
        if (!userName.isNullOrBlank()) it[KEY_USER_NAME] = userName.trim()
    }

    /** Persists a token together with its absolute expiry, pre-computed. */
    suspend fun setToken(accessToken: String, expiresInSeconds: Long) =
        context.credentialsDataStore.edit {
            it[KEY_ACCESS_TOKEN] = accessToken
            it[KEY_EXPIRES_AT] = System.currentTimeMillis() / 1000 + expiresInSeconds
        }

    /**
     * Drops the token but keeps the client configuration and the 学号, so
     * re-authorising does not mean retyping everything.
     */
    suspend fun clearToken() = context.credentialsDataStore.edit {
        it.remove(KEY_ACCESS_TOKEN)
        it.remove(KEY_EXPIRES_AT)
    }

    /** Full sign-out. */
    suspend fun clearAll() = context.credentialsDataStore.edit { it.clear() }

    private companion object {
        val KEY_CLIENT_ID = stringPreferencesKey("client_id")
        val KEY_CLIENT_SECRET = stringPreferencesKey("client_secret")
        val KEY_ACCESS_TOKEN = stringPreferencesKey("access_token")
        val KEY_EXPIRES_AT = longPreferencesKey("expires_at")
        val KEY_USER_ID = stringPreferencesKey("user_id")
        val KEY_USER_NAME = stringPreferencesKey("user_name")
    }
}
