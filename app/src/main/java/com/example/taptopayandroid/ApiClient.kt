package com.example.taptopayandroid

import android.util.Log
import com.example.taptopayandroid.BuildConfig
import com.example.taptopayandroid.PaymentIntentCreationResponse
import com.stripe.stripeterminal.external.models.ConnectionTokenException
import okhttp3.OkHttpClient
import retrofit2.Callback
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.IOException

/**
 * The `ApiClient` is a singleton object used to make calls to our backend and return their results
 */
object ApiClient {
    private const val TAG = "ApiClient"

    private val client = OkHttpClient.Builder()
        .build()
    private val retrofit: Retrofit = Retrofit.Builder()
        .baseUrl(BuildConfig.EXAMPLE_BACKEND_URL)
        .client(client)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
    private val service: BackendService = retrofit.create(BackendService::class.java)

    init {
        Log.d(TAG, "ApiClient initialized with base URL: ${BuildConfig.EXAMPLE_BACKEND_URL}")
        Log.i(TAG, "=== BACKEND CONFIGURATION ===")
        Log.i(TAG, "Backend URL: ${BuildConfig.EXAMPLE_BACKEND_URL}")
        Log.i(TAG, "URL Length: ${BuildConfig.EXAMPLE_BACKEND_URL.length}")
        Log.i(TAG, "=== END BACKEND CONFIG ===")
    }

    @Throws(ConnectionTokenException::class)
    internal fun createConnectionToken(): String {
        Log.d(TAG, "Creating connection token...")
        try {
            val result = service.getConnectionToken().execute()
            Log.d(TAG, "Connection token response - isSuccessful: ${result.isSuccessful}, code: ${result.code()}")
            
            if (result.isSuccessful && result.body() != null) {
                val token = result.body()!!.secret
                Log.d(TAG, "Connection token created successfully: ${token.take(20)}...")
                Log.i(TAG, "=== API CLIENT TOKEN DETAILS ===")
                Log.i(TAG, "Full Token from API: $token")
                Log.i(TAG, "Token Length: ${token.length}")
                Log.i(TAG, "Response Code: ${result.code()}")
                Log.i(TAG, "=== END API CLIENT TOKEN ===")
                return token
            } else {
                Log.e(TAG, "Connection token creation failed - Response: ${result.errorBody()?.string()}")
                throw ConnectionTokenException("Creating connection token failed - Response code: ${result.code()}")
            }
        } catch (e: IOException) {
            Log.e(TAG, "IOException while creating connection token", e)
            throw ConnectionTokenException("Creating connection token failed", e)
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error while creating connection token", e)
            throw ConnectionTokenException("Creating connection token failed", e)
        }
    }

    internal fun createLocation(
        displayName: String?,
        city: String?,
        country: String?,
        line1: String?,
        line2: String?,
        postalCode: String?,
        state: String?,
    ) {
        TODO("Call Backend application to create location")
    }

    internal fun capturePaymentIntent(id: String) {
        Log.d(TAG, "Capturing payment intent: $id")
        try {
            val result = service.capturePaymentIntent(id).execute()
            Log.d(TAG, "Capture payment intent response - isSuccessful: ${result.isSuccessful}, code: ${result.code()}")
            if (!result.isSuccessful) {
                Log.e(TAG, "Failed to capture payment intent - Response: ${result.errorBody()?.string()}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error capturing payment intent", e)
        }
    }

    internal fun cancelPaymentIntent(
        id: String,
        callback: Callback<Void>
    ) {
        Log.d(TAG, "Cancelling payment intent: $id")
        service.cancelPaymentIntent(id).enqueue(callback)
    }

    /**
     * This method is calling the example backend (https://github.com/stripe/example-terminal-backend)
     * to create paymentIntent for Internet based readers, for example WisePOS E. For your own application, you
     * should create paymentIntent in your own merchant backend.
     */
    internal fun createPaymentIntent(
        amount: Long,
        currency: String,
        extendedAuth: Boolean,
        incrementalAuth: Boolean,
        callback: Callback<PaymentIntentCreationResponse>
    ) {
        Log.d(TAG, "Creating payment intent - amount: $amount, currency: $currency, extendedAuth: $extendedAuth, incrementalAuth: $incrementalAuth")
        
        val createPaymentIntentParams = buildMap<String, String> {
            put("amount", amount.toString())
            put("currency", currency)

            if (extendedAuth) {
                put("payment_method_options[card_present[request_extended_authorization]]", "true")
            }
            if (incrementalAuth) {
                put("payment_method_options[card_present[request_incremental_authorization_support]]", "true")
            }
        }

        Log.d(TAG, "Payment intent parameters: $createPaymentIntentParams")
        service.createPaymentIntent(createPaymentIntentParams).enqueue(callback)
    }
}
