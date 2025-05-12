package com.engineerakash.torch

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.MobileAds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private val torchUtil by lazy {
        TorchUtil(this)
    }

    private val maiScope by lazy {
        CoroutineScope(Dispatchers.Main)
    }

    private val backgroundScope by lazy {
        CoroutineScope(Dispatchers.IO)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        val rootLayout = findViewById<ConstraintLayout>(R.id.main)
        val brightNessIv = findViewById<ImageView>(R.id.brightNessIv)
        val torchIv = findViewById<ImageView>(R.id.torchIv)
        val adViewContainer = findViewById<FrameLayout>(R.id.ad_view_container)

        setListeners(brightNessIv, rootLayout, torchIv)

        observeData(rootLayout, brightNessIv)

        initAds(adViewContainer)
    }

    private fun initAds(adViewContainer: FrameLayout) {
        val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // Network is available, load the ad
                loadAd(adViewContainer)
            }
        }

        // Check current network state
        val activeNetwork = connectivityManager.activeNetwork
        val networkCapabilities = connectivityManager.getNetworkCapabilities(activeNetwork)

        if (networkCapabilities != null && networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            // Internet is available, load the ad immediately
            loadAd(adViewContainer)
        } else {
            // No internet, register callback to listen for connectivity changes
            connectivityManager.registerNetworkCallback(networkRequest, networkCallback)
        }
    }

    private fun loadAd(adViewContainer: FrameLayout) {
        backgroundScope.launch {
            // Initialize the Google Mobile Ads SDK on a background thread.
            MobileAds.initialize(this@MainActivity) {}
        }

        maiScope.launch {
            val adView = AdView(this@MainActivity)
            // Use a test ad unit ID during development
            // See https://developers.google.com/admob/android/test-ads
            adView.adUnitId = "ca-app-pub-3940256099942544/6300978111"

            // Request an anchored adaptive banner with a width of 360.
            adView.setAdSize(
                AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(
                    this@MainActivity, 360
                )
            )

            // Replace ad container with new ad view.
            adViewContainer.removeAllViews()
            adViewContainer.addView(adView)

            val adRequest = AdRequest.Builder().build()
            adView.loadAd(adRequest)
        }
    }

    private fun setListeners(
        brightNessIv: ImageView,
        rootLayout: ConstraintLayout,
        torchIv: ImageView
    ) {
        brightNessIv.setOnClickListener {
            torchClicked(rootLayout, brightNessIv)
        }

        torchIv.setOnClickListener {
            torchClicked(rootLayout, brightNessIv)
        }
    }

    private fun observeData(
        rootLayout: ConstraintLayout,
        brightNessIv: ImageView
    ) {
        torchUtil.isTorchOn.observe(this) { isTorchOn ->

            if (isTorchOn) {
                rootLayout.setBackgroundResource(android.R.color.white)
                brightNessIv.visibility = View.VISIBLE
            } else {
                rootLayout.setBackgroundResource(android.R.color.darker_gray)
                brightNessIv.visibility = View.GONE
            }
        }
    }

    private fun torchClicked(rootLayout: ConstraintLayout, brightNessIv: ImageView) {
        if (torchUtil.isTorchOn.value == true) {
            // Turn OFF the torch
            torchUtil.turnOnTorch(false)
        } else {
            // Turn ON the torch
            torchUtil.turnOnTorch(true)
        }
    }

    override fun onDestroy() {
        torchUtil.turnOnTorch(false)
        super.onDestroy()
    }
}