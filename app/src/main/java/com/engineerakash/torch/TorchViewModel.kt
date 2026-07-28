package com.engineerakash.torch

import android.app.Application
import android.content.Context
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TorchViewModel(private val application: Application) : AndroidViewModel(application) {

    companion object {
        private const val SOS_UNIT_MS = 200L
        private const val MIN_STROBE_RATE = 1
        private const val MAX_STROBE_RATE = 10
        const val DEFAULT_STROBE_RATE = 5
    }

    private val cameraManager: CameraManager? =
        application.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
    private val cameraId: String? = resolveFlashCameraId()

    private val torchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            if (cameraId == this@TorchViewModel.cameraId) {
                lastKnownTorchOn = enabled
                _isTorchOn.postValue(enabled)
            }
        }
    }

    // Synchronously readable torch state: the system callback echo arrives a few main-loop
    // messages after setTorchMode, so deciding a toggle off _isTorchOn.value alone would make
    // a fast double-tap turn the torch ON twice instead of toggling it back off.
    @Volatile
    private var lastKnownTorchOn = false

    private val _selectedTab = MutableLiveData(TorchMode.TORCH)
    val selectedTab: LiveData<TorchMode> = _selectedTab

    // Raw hardware truth, reported by the system TorchCallback
    private val _isTorchOn = MutableLiveData(false)
    val isTorchOn: LiveData<Boolean> = _isTorchOn

    /**
     * What the Torch screen shows: on only while the Torch tab is selected.
     * During strobe/SOS the hardware flips up to 20x/sec; pinning this to false
     * outside the Torch tab keeps those flips from thrashing UI observers.
     */
    val torchUiOn: LiveData<Boolean> = MediatorLiveData<Boolean>().apply {
        fun update() {
            val next = _selectedTab.value == TorchMode.TORCH && _isTorchOn.value == true
            if (value != next) value = next
        }
        addSource(_isTorchOn) { update() }
        addSource(_selectedTab) { update() }
    }

    // Which looping mode (STROBE/SOS) is currently running, null when none
    private val _activeMode = MutableLiveData<TorchMode?>(null)
    val activeMode: LiveData<TorchMode?> = _activeMode

    private val _strobeRate = MutableLiveData(DEFAULT_STROBE_RATE)
    val strobeRate: LiveData<Int> = _strobeRate

    // Read by the strobe loop on a background dispatcher, written from the main thread
    @Volatile
    private var strobeRateHz = DEFAULT_STROBE_RATE

    private val _errorMessage = MutableLiveData<String?>()
    val errorMessage: LiveData<String?> = _errorMessage

    private var activeJob: Job? = null

    init {
        cameraManager?.registerTorchCallback(torchCallback, null)
    }

    fun selectTab(mode: TorchMode) {
        if (_selectedTab.value == mode) return
        stopAll()
        _selectedTab.value = mode
    }

    fun toggleTorch() {
        val turnOn = !lastKnownTorchOn
        stopAll()
        if (turnOn && setTorchInternal(true)) {
            lastKnownTorchOn = true
        }
    }

    fun toggleStrobe() {
        if (_activeMode.value == TorchMode.STROBE) {
            stopAll()
        } else {
            launchExclusive(TorchMode.STROBE) { strobeLoop() }
        }
    }

    fun toggleSos() {
        if (_activeMode.value == TorchMode.SOS) {
            stopAll()
        } else {
            launchExclusive(TorchMode.SOS) { sosLoop() }
        }
    }

    fun setStrobeRate(rate: Int) {
        val clamped = rate.coerceIn(MIN_STROBE_RATE, MAX_STROBE_RATE)
        strobeRateHz = clamped
        if (_strobeRate.value != clamped) {
            _strobeRate.value = clamped
        }
    }

    fun stopAll() {
        // Cancel but keep the reference: launchExclusive must still be able to join the
        // old job so its finally can't extinguish the next mode's first flash
        activeJob?.cancel()
        setTorchInternal(false)
        lastKnownTorchOn = false
    }

    fun onErrorShown() {
        _errorMessage.value = null
    }

    private fun launchExclusive(mode: TorchMode, block: suspend CoroutineScope.() -> Unit) {
        val previous = activeJob
        activeJob = viewModelScope.launch {
            // The old job's finally (torch off) must fully finish before the new
            // mode starts, or it could extinguish the new mode's first flash
            previous?.cancelAndJoin()
            _activeMode.value = mode
            try {
                withContext(Dispatchers.Default, block)
            } finally {
                setTorchInternal(false)
                if (_activeMode.value == mode) {
                    _activeMode.value = null
                }
            }
        }
    }

    private suspend fun CoroutineScope.strobeLoop() {
        while (isActive) {
            val halfPeriodMs = 500L / strobeRateHz
            if (!setTorchInternal(true)) return
            delay(halfPeriodMs)
            setTorchInternal(false)
            delay(halfPeriodMs)
        }
    }

    // (torch on, duration in units): S = 3 dots, O = 3 dashes, S = 3 dots.
    // Dot 1 unit, dash 3; gaps: 1 within a letter, 3 between letters, 7 between repeats.
    private val sosPattern = listOf(
        true to 1, false to 1, true to 1, false to 1, true to 1, false to 3,
        true to 3, false to 1, true to 3, false to 1, true to 3, false to 3,
        true to 1, false to 1, true to 1, false to 1, true to 1, false to 7,
    )

    private suspend fun CoroutineScope.sosLoop() {
        while (isActive) {
            for ((on, units) in sosPattern) {
                if (on) {
                    if (!setTorchInternal(true)) return
                } else {
                    setTorchInternal(false)
                }
                delay(units * SOS_UNIT_MS)
            }
        }
    }

    /** @return false when the flash is unavailable or the hardware call failed. */
    private fun setTorchInternal(on: Boolean): Boolean {
        val manager = cameraManager ?: return false
        val id = cameraId
        if (id == null) {
            if (on) {
                _errorMessage.postValue(application.getString(R.string.flash_not_available))
            }
            return false
        }
        return try {
            manager.setTorchMode(id, on)
            true
        } catch (e: CameraAccessException) {
            e.printStackTrace()
            if (on) {
                _errorMessage.postValue(application.getString(R.string.something_is_not_right))
            }
            false
        } catch (e: IllegalArgumentException) {
            e.printStackTrace()
            false
        }
    }

    private fun resolveFlashCameraId(): String? = try {
        cameraManager?.cameraIdList?.firstOrNull { id ->
            cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
    } catch (e: CameraAccessException) {
        e.printStackTrace()
        null
    }

    override fun onCleared() {
        stopAll()
        cameraManager?.unregisterTorchCallback(torchCallback)
        super.onCleared()
    }
}
