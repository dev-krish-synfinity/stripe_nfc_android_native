
package com.example.taptopayandroid

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import com.example.taptopayandroid.fragments.ConnectReaderFragment
import com.example.taptopayandroid.fragments.PaymentDetails
import com.stripe.stripeterminal.Terminal
import com.stripe.stripeterminal.external.callable.*
import com.stripe.stripeterminal.external.models.*
import com.stripe.stripeterminal.log.LogLevel
import kotlinx.coroutines.flow.MutableStateFlow
import retrofit2.Call
import retrofit2.Response

var SKIP_TIPPING: Boolean = true

class MainActivity : AppCompatActivity(), NavigationListener {
    companion object {
        private const val TAG = "MainActivity"
    }
    // Register the permissions callback to handles the response to the system permissions dialog.
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
        ::onPermissionResult
    )

    @RequiresApi(Build.VERSION_CODES.S)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate() called")
        setContentView(R.layout.activity_main)

        navigateTo(ConnectReaderFragment.TAG, ConnectReaderFragment(), false)

        requestPermissionsIfNecessarySdk31()

        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            Log.d(TAG, "Bluetooth permission granted")
            BluetoothAdapter.getDefaultAdapter()?.let { adapter ->
                if (!adapter.isEnabled) {
                    Log.d(TAG, "Enabling Bluetooth adapter")
                    adapter.enable()
                } else {
                    Log.d(TAG, "Bluetooth adapter already enabled")
                }
            }
        } else {
            Log.w(TAG, "Failed to acquire Bluetooth permission")
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun requestPermissionsIfNecessarySdk31() {
        // Check for location and bluetooth permissions
        val deniedPermissions = mutableListOf<String>().apply {
            if (!isGranted(Manifest.permission.ACCESS_FINE_LOCATION)) add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (!isGranted(Manifest.permission.BLUETOOTH_CONNECT)) add(Manifest.permission.BLUETOOTH_CONNECT)
            if (!isGranted(Manifest.permission.BLUETOOTH_SCAN)) add(Manifest.permission.BLUETOOTH_SCAN)
        }.toTypedArray()

        if (deniedPermissions.isNotEmpty()) {
            // If we don't have them yet, request them before doing anything else
            requestPermissionLauncher.launch(deniedPermissions)
        } else if (!Terminal.isInitialized() && verifyGpsEnabled()) {
            initialize()
        }
    }

    private fun isGranted(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            permission
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun onPermissionResult(result: Map<String, Boolean>) {
        val deniedPermissions: List<String> = result
            .filter { !it.value }
            .map { it.key }

        // If we receive a response to our permission check, initialize
        if (deniedPermissions.isEmpty() && !Terminal.isInitialized() && verifyGpsEnabled()) {
            initialize()
        }
    }

    private fun verifyGpsEnabled(): Boolean {
        val locationManager: LocationManager? =
            applicationContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager?
        var gpsEnabled = false

        try {
            gpsEnabled = locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) ?: false
        } catch (exception: Exception) {}

        if (!gpsEnabled) {
            // notify user
        }

        return gpsEnabled
    }

    private fun initialize() {
        Log.d(TAG, "Initializing Terminal...")
        // Initialize the Terminal as soon as possible
        try {
            Terminal.initTerminal(
                applicationContext, LogLevel.VERBOSE, TokenProvider(),
                TerminalEventListener()
            )
            Log.d(TAG, "Terminal initialized successfully")
        } catch (e: TerminalException) {
            Log.e(TAG, "Failed to initialize Terminal", e)
            throw RuntimeException(
                "Location services are required in order to initialize " +
                        "the Terminal.",
                e
            )
        }

        loadLocations()
    }

    private val mutableListState = MutableStateFlow(LocationListState())

    private val locationCallback = object : LocationListCallback {
        override fun onFailure(e: TerminalException) {
            Log.e(TAG, "Failed to load locations", e)
            e.printStackTrace()
        }

        override fun onSuccess(locations: List<Location>, hasMore: Boolean) {
            Log.d(TAG, "Loaded ${locations.size} locations, hasMore: $hasMore")
            mutableListState.value = mutableListState.value.let {
                it.copy(
                    locations = it.locations + locations,
                    hasMore = hasMore,
                    isLoading = false,
                )
            }
        }
    }

    private fun collectPayment(
        amount: Long,
        currency: String,
        skipTipping: Boolean,
        extendedAuth: Boolean,
        incrementalAuth: Boolean
    ) {
        Log.d(TAG, "Starting payment collection - amount: $amount, currency: $currency, skipTipping: $skipTipping")
        SKIP_TIPPING = skipTipping

        ApiClient.createPaymentIntent(
            amount,
            currency,
            extendedAuth,
            incrementalAuth,
            callback = object : retrofit2.Callback<PaymentIntentCreationResponse> {
                override fun onResponse(
                    call: Call<PaymentIntentCreationResponse>,
                    response: Response<PaymentIntentCreationResponse>
                ) {
                    Log.d(TAG, "Payment intent creation response - isSuccessful: ${response.isSuccessful}, code: ${response.code()}")
                    if (response.isSuccessful && response.body() != null) {
                        val secret = response.body()!!.secret
                        Log.d(TAG, "Retrieving payment intent with secret: ${secret.take(20)}...")
                        // Use client_secret from the response
                        Terminal.getInstance().retrievePaymentIntent(
                            secret,
                            createPaymentIntentCallback
                        )
                    } else {
                        Log.e(TAG, "Payment intent creation failed - Response: ${response.errorBody()?.string()}")
                    }
                }

                override fun onFailure(
                    call: Call<PaymentIntentCreationResponse>,
                    t: Throwable
                ) {
                    Log.e(TAG, "Payment intent creation failed", t)
                    t.printStackTrace()
                }
            }
        )
    }


    private val createPaymentIntentCallback by lazy {
        object : PaymentIntentCallback {
            override fun onSuccess(paymentIntent: PaymentIntent) {
                Log.d(TAG, "Payment intent retrieved successfully: ${paymentIntent.id}")
                val skipTipping = SKIP_TIPPING

                val collectConfig = CollectConfiguration.Builder()
                    .skipTipping(skipTipping)
                    .build()

                Log.d(TAG, "Starting payment method collection with skipTipping: $skipTipping")
                Terminal.getInstance().collectPaymentMethod(
                    paymentIntent, collectPaymentMethodCallback, collectConfig
                )
            }

            override fun onFailure(e: TerminalException) {
                Log.e(TAG, "Failed to retrieve payment intent", e)
                e.printStackTrace()
            }
        }
    }

    private val collectPaymentMethodCallback by lazy {
        object : PaymentIntentCallback {
            override fun onSuccess(paymentIntent: PaymentIntent) {
                Log.d(TAG, "Payment method collected successfully: ${paymentIntent.id}")
                Log.d(TAG, "Processing payment...")
                Terminal.getInstance().processPayment(paymentIntent, processPaymentCallback)
            }

            override fun onFailure(e: TerminalException) {
                Log.e(TAG, "Failed to collect payment method", e)
                e.printStackTrace()
            }
        }
    }

    private val processPaymentCallback by lazy {
        object : PaymentIntentCallback {
            override fun onSuccess(paymentIntent: PaymentIntent) {
                Log.d(TAG, "Payment processed successfully: ${paymentIntent.id}")
                Log.d(TAG, "Capturing payment intent...")
                ApiClient.capturePaymentIntent(paymentIntent.id)

                //TODO : Return to previous Screen
                Log.d(TAG, "Navigating to payment details screen")
                navigateTo(PaymentDetails.TAG, PaymentDetails(), true)
            }

            override fun onFailure(e: TerminalException) {
                Log.e(TAG, "Failed to process payment", e)
                e.printStackTrace()
            }
        }
    }

    private fun loadLocations() {
        Log.d(TAG, "Loading locations...")
        Terminal.getInstance().listLocations(
            ListLocationsParameters.Builder().apply {
                limit = 100
            }.build(),
            locationCallback
        )
    }

    private fun connectReader(){
        Log.d(TAG, "Starting connectReader()")
        
        try {
            // Check if locations are available
            if (mutableListState.value.locations.isEmpty()) {
                Log.e(TAG, "No locations available for reader connection")
                runOnUiThread {
                    // Reset button text on error
                    val fragment = supportFragmentManager.findFragmentByTag(ConnectReaderFragment.TAG) as? ConnectReaderFragment
                    fragment?.view?.findViewById<Button>(R.id.connect_reader_button)?.text = "Connect reader"
                }
                return
            }

            val locationId = mutableListState.value.locations[0].id
            Log.d(TAG, "Using location ID: $locationId")

            val config = DiscoveryConfiguration(
                timeout = 0,
                discoveryMethod = DiscoveryMethod.LOCAL_MOBILE,
                isSimulated = false,
                location = locationId ?: ""
            )

            Log.d(TAG, "Starting reader discovery...")
            Terminal.getInstance().discoverReaders(config, discoveryListener = object :
                DiscoveryListener {
                override fun onUpdateDiscoveredReaders(readers: List<Reader>) {
                    Log.d(TAG, "Discovered ${readers.size} readers")
                    
                    val onlineReaders = readers.filter { it.networkStatus != Reader.NetworkStatus.OFFLINE }
                    Log.d(TAG, "Online readers: ${onlineReaders.size}")
                    
                    if (onlineReaders.isEmpty()) {
                        Log.e(TAG, "No online readers found")
                        runOnUiThread {
                            // Reset button text on error
                            val fragment = supportFragmentManager.findFragmentByTag(ConnectReaderFragment.TAG) as? ConnectReaderFragment
                            fragment?.view?.findViewById<Button>(R.id.connect_reader_button)?.text = "Connect reader"
                        }
                        return
                    }

                    val reader = onlineReaders[0]
                    Log.d(TAG, "Connecting to reader: ${reader.id ?: "unknown"}")

                    val connectionConfig = ConnectionConfiguration.LocalMobileConnectionConfiguration(locationId ?: "")

                    Terminal.getInstance().connectLocalMobileReader(
                        reader,
                        connectionConfig,
                        object: ReaderCallback {
                            override fun onFailure(e: TerminalException) {
                                Log.e(TAG, "Failed to connect to reader", e)
                                runOnUiThread {
                                    // Reset button text on error
                                    val fragment = supportFragmentManager.findFragmentByTag(ConnectReaderFragment.TAG) as? ConnectReaderFragment
                                    fragment?.view?.findViewById<Button>(R.id.connect_reader_button)?.text = "Connect reader"
                                }
                            }

                            override fun onSuccess(reader: Reader) {
                                Log.d(TAG, "Successfully connected to reader: ${reader.id}")
                                // Update the UI with the location name and terminal ID
                                runOnUiThread {
                                    val manager: FragmentManager = supportFragmentManager
                                    val fragment: Fragment? = manager.findFragmentByTag(ConnectReaderFragment.TAG)

                                    if(reader.id != null && mutableListState.value.locations[0].displayName != null){
                                        (fragment as? ConnectReaderFragment)?.updateReaderId(
                                            mutableListState.value.locations[0].displayName!!, reader.id!!
                                        )
                                    }
                                }
                            }
                        }
                    )
                }
            }, object : Callback {
                override fun onSuccess() {
                    Log.d(TAG, "Finished discovering readers")
                }

                override fun onFailure(e: TerminalException) {
                    Log.e(TAG, "Reader discovery failed", e)
                    runOnUiThread {
                        // Reset button text on error
                        val fragment = supportFragmentManager.findFragmentByTag(ConnectReaderFragment.TAG) as? ConnectReaderFragment
                        fragment?.view?.findViewById<Button>(R.id.connect_reader_button)?.text = "Connect reader"
                    }
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Exception in connectReader()", e)
            runOnUiThread {
                // Reset button text on error
                val fragment = supportFragmentManager.findFragmentByTag(ConnectReaderFragment.TAG) as? ConnectReaderFragment
                fragment?.view?.findViewById<Button>(R.id.connect_reader_button)?.text = "Connect reader"
            }
        }
    }

    // Navigate to Fragment
    private fun navigateTo(
        tag: String,
        fragment: Fragment,
        replace: Boolean = true,
        addToBackStack: Boolean = false,
    ) {
        val frag = supportFragmentManager.findFragmentByTag(tag) ?: fragment
        supportFragmentManager
            .beginTransaction()
            .apply {
                if (replace) {
                    replace(R.id.container, frag, tag)
                } else {
                    add(R.id.container, frag, tag)
                }

                if (addToBackStack) {
                    addToBackStack(tag)
                }
            }
            .commitAllowingStateLoss()
    }

    override fun onConnectReader(){
        connectReader()
    }

    override fun onCollectPayment(
        amount: Long,
        currency: String,
        skipTipping: Boolean,
        extendedAuth: Boolean,
        incrementalAuth: Boolean
    ){
        collectPayment(amount, currency, skipTipping, extendedAuth, incrementalAuth)
    }

    override fun onNavigateToPaymentDetails(){
        // Navigate to the fragment that will show the payment details
        navigateTo(PaymentDetails.TAG, PaymentDetails(), true)
    }

    override fun onCancel(){
        navigateTo(ConnectReaderFragment.TAG, ConnectReaderFragment(), true)
    }
}