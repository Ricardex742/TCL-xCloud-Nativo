package com.xcloud.tclnative.auth

import android.content.Context

data class OAuthToken(val ticket: String, val refreshToken: String?, val expiresAtMs: Long?, val clientId: String)

interface OAuthTokenStore {
    fun read(): OAuthToken?
    fun write(token: OAuthToken)
    fun clear()
}

class PreferencesTokenStore(context: Context) : OAuthTokenStore {
    private val prefs = context.getSharedPreferences("xcloud_native_auth", Context.MODE_PRIVATE)
    override fun read(): OAuthToken? {
        val ticket = prefs.getString("rps_ticket", null)?.takeIf { it.isNotBlank() } ?: return null
        return OAuthToken(ticket, prefs.getString("refresh_token", null)?.takeIf { it.isNotBlank() },
            prefs.getLong("expires_at_ms", prefs.getLong("expires_at", 0)).takeIf { it > 0 },
            prefs.getString("client_id", null) ?: XalAuthenticator.DEVICE_CLIENT_ID)
    }
    override fun write(token: OAuthToken) {
        prefs.edit().putString("rps_ticket", token.ticket).putString("refresh_token", token.refreshToken)
            .putLong("expires_at_ms", token.expiresAtMs ?: 0).putString("client_id", token.clientId).apply()
    }
    override fun clear() { prefs.edit().clear().apply() }
}
