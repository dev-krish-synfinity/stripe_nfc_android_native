package com.example.taptopayandroid

import android.util.Log
import com.stripe.stripeterminal.external.callable.ConnectionTokenCallback
import com.stripe.stripeterminal.external.callable.ConnectionTokenProvider
import com.stripe.stripeterminal.external.models.ConnectionTokenException

/**
 * A simple implementation of the [ConnectionTokenProvider] interface. We just request a
 * new token from our backend simulator and forward any exceptions along to the SDK.
 */
class TokenProvider : ConnectionTokenProvider {
    companion object {
        private const val TAG = "TokenProvider"
    }

    override fun fetchConnectionToken(callback: ConnectionTokenCallback) {
        Log.d(TAG, "Fetching connection token...")
        try {
            val token = ApiClient.createConnectionToken()
            Log.d(TAG, "Connection token fetched successfully")
            Log.i(TAG, "=== STRIPE CONNECTION TOKEN ===")
            Log.i(TAG, "Full Token: $token")
            Log.i(TAG, "Token Length: ${token.length}")
            Log.i(TAG, "Token Preview: ${token.take(50)}...")
            Log.i(TAG, "=== END STRIPE TOKEN ===")
            callback.onSuccess(token)
        } catch (e: ConnectionTokenException) {
            Log.e(TAG, "Failed to fetch connection token", e)
            callback.onFailure(e)
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error while fetching connection token", e)
            callback.onFailure(ConnectionTokenException("Unexpected error", e))
        }
    }
}
