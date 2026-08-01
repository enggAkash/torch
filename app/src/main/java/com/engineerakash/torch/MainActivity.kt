package com.engineerakash.torch

import android.animation.Animator
import android.animation.AnimatorInflater
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.MobileAds
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private val torchViewModel by viewModels<TorchViewModel>()

    private var adView: AdView? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /**
     * Anchored adaptive banner for a 360dp-wide slot. Resolved once per activity so the
     * space reserved up front is exactly what the loaded ad occupies; the activity is
     * recreated on rotation, so this picks up the new orientation's height.
     */
    private val adSize by lazy {
        AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(this, 360)
    }

    private lateinit var mainRoot: View
    private lateinit var modeTabLayout: TabLayout
    private lateinit var torchModeRoot: View
    private lateinit var strobeModeRoot: View
    private lateinit var sosModeRoot: View
    private lateinit var brightNessIv: ImageView
    private lateinit var torchStatusTitleTv: TextView
    private lateinit var torchStatusCaptionTv: TextView
    private lateinit var torchToggleBtn: MaterialButton
    private lateinit var strobeSpeedValueTv: TextView
    private lateinit var strobeSpeedSlider: Slider
    private lateinit var strobeToggleBtn: MaterialButton
    private lateinit var sosToggleBtn: MaterialButton
    private lateinit var strobePulse: Pulse
    private lateinit var sosPulse: Pulse

    // Mirrors TorchViewModel.activeMode so onStart can resume the right pulse
    private var runningMode: TorchMode? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        findViews()

        setListeners()

        observeData()

        initAds(findViewById(R.id.ad_view_container))
    }

    private fun findViews() {
        mainRoot = findViewById(R.id.main)
        modeTabLayout = findViewById(R.id.modeTabLayout)
        torchModeRoot = findViewById(R.id.torchModeRoot)
        strobeModeRoot = findViewById(R.id.strobeModeRoot)
        sosModeRoot = findViewById(R.id.sosModeRoot)
        brightNessIv = findViewById(R.id.brightNessIv)
        torchStatusTitleTv = findViewById(R.id.torchStatusTitleTv)
        torchStatusCaptionTv = findViewById(R.id.torchStatusCaptionTv)
        torchToggleBtn = findViewById(R.id.torchToggleBtn)
        strobeSpeedValueTv = findViewById(R.id.strobeSpeedValueTv)
        strobeSpeedSlider = findViewById(R.id.strobeSpeedSlider)
        strobeToggleBtn = findViewById(R.id.strobeToggleBtn)
        sosToggleBtn = findViewById(R.id.sosToggleBtn)
        strobePulse = Pulse(findViewById(R.id.strobeIv))
        sosPulse = Pulse(findViewById(R.id.sosRingsIv))
    }

    private fun initAds(adViewContainer: FrameLayout) {
        reserveAdSpace(adViewContainer)

        val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager

        // Check current network state
        val activeNetwork = connectivityManager.activeNetwork
        val networkCapabilities = connectivityManager.getNetworkCapabilities(activeNetwork)

        if (networkCapabilities != null && networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            // Internet is available, load the ad immediately
            loadAd(adViewContainer)
        } else {
            // No internet: wait for connectivity, once. The callback unregisters itself
            // on first fire and again in onDestroy, so recreations can't accumulate
            // registrations (100 per process throws TooManyRequestsException)
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    // Fires on a connectivity thread; lifecycleScope hops to main and is
                    // cancelled at destroy, so a late network can't touch dead views
                    lifecycleScope.launch {
                        unregisterNetworkCallback()
                        loadAd(adViewContainer)
                    }
                }
            }
            networkCallback = callback
            val networkRequest = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            connectivityManager.registerNetworkCallback(networkRequest, callback)
        }
    }

    private fun unregisterNetworkCallback() {
        val callback = networkCallback ?: return
        // Null first: safe to reach from both onAvailable and onDestroy
        networkCallback = null
        (getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager)
            .unregisterNetworkCallback(callback)
    }

    /**
     * Claim the banner's slot before it loads. Without it the container is 0dp tall
     * until an ad arrives, and the CTA below the mode content jumps upward mid-session.
     */
    private fun reserveAdSpace(adViewContainer: FrameLayout) {
        val heightPx = adSize.getHeightInPixels(this)
        if (heightPx > 0) {
            adViewContainer.updateLayoutParams { height = heightPx }
        }
    }

    /** Main thread only. */
    private fun loadAd(adViewContainer: FrameLayout) {
        if (isDestroyed || adView != null) return

        lifecycleScope.launch(Dispatchers.IO) {
            // Initialize the Google Mobile Ads SDK on a background thread.
            // applicationContext so the SDK's init registry can't pin this activity.
            MobileAds.initialize(applicationContext) {}
        }

        val newAdView = AdView(this)
        // Test unit in debug, live unit in release. See app/build.gradle.kts
        newAdView.adUnitId = BuildConfig.AD_UNIT_ID

        newAdView.setAdSize(adSize)
        adView = newAdView

        // Replace ad container with new ad view.
        adViewContainer.removeAllViews()
        adViewContainer.addView(newAdView)

        val adRequest = AdRequest.Builder().build()
        newAdView.loadAd(adRequest)
    }

    private fun setListeners() {
        modeTabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                torchViewModel.selectTab(TorchMode.entries[tab.position])
            }

            override fun onTabUnselected(tab: TabLayout.Tab) = Unit

            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })

        findViewById<ImageButton>(R.id.shareBtn).setOnClickListener {
            shareApp()
        }

        torchToggleBtn.setOnClickListener {
            torchViewModel.toggleTorch()
        }

        findViewById<ImageView>(R.id.torchIv).setOnClickListener {
            torchViewModel.toggleTorch()
        }

        strobeToggleBtn.setOnClickListener {
            torchViewModel.toggleStrobe()
        }

        findViewById<ImageView>(R.id.strobeIv).setOnClickListener {
            torchViewModel.toggleStrobe()
        }

        strobeSpeedSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                torchViewModel.setStrobeRate(value.toInt())
            }
        }

        // Drag tooltip and TalkBack should say "5 flashes / sec", not a bare "5"
        strobeSpeedSlider.setLabelFormatter { value ->
            val rate = value.toInt()
            resources.getQuantityString(R.plurals.flashes_per_sec, rate, rate)
        }

        sosToggleBtn.setOnClickListener {
            torchViewModel.toggleSos()
        }

        findViewById<View>(R.id.sosIllustrationContainer).setOnClickListener {
            torchViewModel.toggleSos()
        }
    }

    private fun observeData() {
        torchViewModel.selectedTab.observe(this) { mode ->
            torchModeRoot.visibility = if (mode == TorchMode.TORCH) View.VISIBLE else View.GONE
            strobeModeRoot.visibility = if (mode == TorchMode.STROBE) View.VISIBLE else View.GONE
            sosModeRoot.visibility = if (mode == TorchMode.SOS) View.VISIBLE else View.GONE

            // Keep the tab bar in sync after rotation without re-triggering selectTab
            if (modeTabLayout.selectedTabPosition != mode.ordinal) {
                modeTabLayout.getTabAt(mode.ordinal)?.select()
            }
        }

        torchViewModel.torchUiOn.observe(this) { isOn ->
            // INVISIBLE (not GONE) so the illustration doesn't jump when toggling
            brightNessIv.visibility = if (isOn) View.VISIBLE else View.INVISIBLE
            torchStatusTitleTv.setText(if (isOn) R.string.flashlight_is_on else R.string.flashlight_is_off)
            torchStatusCaptionTv.setText(if (isOn) R.string.tap_to_turn_off else R.string.tap_to_turn_on)
            torchToggleBtn.setText(if (isOn) R.string.turn_off else R.string.turn_on)
            updateScreenBackground()
        }

        torchViewModel.activeMode.observe(this) { activeMode ->
            runningMode = activeMode

            val strobeRunning = activeMode == TorchMode.STROBE
            strobeToggleBtn.setText(if (strobeRunning) R.string.stop_strobe else R.string.start_strobe)
            strobeToggleBtn.setIconResource(if (strobeRunning) R.drawable.ic_pause else R.drawable.ic_play)
            if (strobeRunning) strobePulse.start() else strobePulse.stop()

            val sosRunning = activeMode == TorchMode.SOS
            sosToggleBtn.setText(if (sosRunning) R.string.stop_sos else R.string.start_sos)
            sosToggleBtn.setIconResource(if (sosRunning) R.drawable.ic_pause else R.drawable.ic_play)
            if (sosRunning) sosPulse.start() else sosPulse.stop()

            updateScreenBackground()
        }

        torchViewModel.strobeRate.observe(this) { rate ->
            val rateText = resources.getQuantityString(R.plurals.flashes_per_sec, rate, rate)
            strobeSpeedValueTv.text = rateText
            ViewCompat.setStateDescription(strobeSpeedSlider, rateText)
            // Restore the slider after rotation; the check avoids a feedback loop
            if (strobeSpeedSlider.value.toInt() != rate) {
                strobeSpeedSlider.value = rate.toFloat()
            }
        }

        torchViewModel.errorMessage.observe(this) { message ->
            if (message != null) {
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                torchViewModel.onErrorShown()
            }
        }
    }

    /** Slightly grey while idle, the brighter lavender while any mode has the light going. */
    private fun updateScreenBackground() {
        val lit = torchViewModel.torchUiOn.value == true || runningMode != null
        mainRoot.setBackgroundResource(
            if (lit) R.color.screen_background_lit else R.color.screen_background
        )
    }

    override fun onStart() {
        super.onStart()
        // activeMode is unchanged across a stop/start, so LiveData won't re-deliver it
        when (runningMode) {
            TorchMode.STROBE -> strobePulse.start()
            TorchMode.SOS -> sosPulse.start()
            else -> Unit
        }
    }

    override fun onResume() {
        super.onResume()
        adView?.resume()
    }

    override fun onPause() {
        adView?.pause()
        super.onPause()
    }

    override fun onStop() {
        // The light keeps flashing in the background, but the pulse has nothing to draw there
        strobePulse.stop()
        sosPulse.stop()
        super.onStop()
    }

    private fun shareApp() {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.share_app_subject))
            putExtra(Intent.EXTRA_TEXT, getString(R.string.share_app_message))
        }
        startActivity(Intent.createChooser(shareIntent, getString(R.string.share_via)))
    }

    override fun onDestroy() {
        unregisterNetworkCallback()
        adView?.destroy()
        adView = null

        if (!isChangingConfigurations) {
            // user is closing the app, stop any light activity
            torchViewModel.stopAll()
        }
        super.onDestroy()
    }

    /**
     * Breathing illustration for a running mode: the on-screen counterpart of the
     * flashing light, so the screen shows more than a changed button label.
     */
    private class Pulse(private val target: View) {
        private var animator: Animator? = null

        fun start() {
            val current = animator ?: AnimatorInflater
                .loadAnimator(target.context, R.animator.pulse_illustration)
                .also {
                    it.setTarget(target)
                    animator = it
                }
            if (!current.isRunning) {
                current.start()
            }
        }

        fun stop() {
            animator?.cancel()
            // cancel() leaves the view wherever the pulse was, so restore the resting look
            target.scaleX = 1f
            target.scaleY = 1f
            target.alpha = 1f
        }
    }
}
