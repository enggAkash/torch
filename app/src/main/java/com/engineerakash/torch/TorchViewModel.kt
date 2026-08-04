package com.engineerakash.torch

import android.app.Application
import android.content.Context
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

class TorchViewModel(private val application: Application) : AndroidViewModel(application) {

    companion object {
        private const val SOS_UNIT_MS = 200L
        private const val MIN_STROBE_RATE = 1
        private const val MAX_STROBE_RATE = 10
        const val DEFAULT_STROBE_RATE = 5
    }

    private val cameraManager: CameraManager? =
        application.getSystemService(Context.CAMERA_SERVICE) as? CameraManager

    // The only thread that talks to CameraManager: keeps its slow binder calls off the
    // main thread (they can stall for seconds behind a wedged camera HAL) and makes
    // every setTorchMode FIFO, so a queued off can never overtake a later on.
    private val torchExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "torch-camera") }
    private val torchDispatcher = torchExecutor.asCoroutineDispatcher()

    // Resolved asynchronously on the torch thread; stays null when there is no flash unit
    @Volatile
    private var cameraId: String? = null

    private val torchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            if (cameraId == this@TorchViewModel.cameraId) {
                val wasOn = lastKnownTorchOn
                lastKnownTorchOn = enabled
                _isTorchOn.postValue(enabled)
                // External flips (quick-settings tile, camera app claiming the flash)
                // must move the auto-off countdown with them: cancel it when the light
                // it guards dies, arm it when a torch appears. In-app paths write
                // lastKnownTorchOn before their echo lands here, so self-initiated
                // changes no-op; the TORCH-tab guard in scheduleAutoOff keeps
                // strobe/SOS flips from ever arming a countdown.
                if (enabled != wasOn) {
                    if (enabled) scheduleAutoOff() else cancelAutoOff()
                }
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

    // Clamped on load in case a stale or hand-edited pref falls outside the slider range
    private val initialStrobeRate = TorchPrefs.loadStrobeRate(application, DEFAULT_STROBE_RATE)
        .coerceIn(MIN_STROBE_RATE, MAX_STROBE_RATE)

    private val _strobeRate = MutableLiveData(initialStrobeRate)
    val strobeRate: LiveData<Int> = _strobeRate

    // Read by the strobe loop on a background dispatcher, written from the main thread
    @Volatile
    private var strobeRateHz = initialStrobeRate

    private val _errorMessage = MutableLiveData<String?>()
    val errorMessage: LiveData<String?> = _errorMessage

    private val _autoOffSetting =
        MutableLiveData<AutoOffSetting>(TorchPrefs.loadAutoOffSetting(application))
    val autoOffSetting: LiveData<AutoOffSetting> = _autoOffSetting

    // Seconds left on a running countdown; null while none is running
    private val _autoOffRemainingSecs = MutableLiveData<Long?>(null)
    val autoOffRemainingSecs: LiveData<Long?> = _autoOffRemainingSecs

    // Deliberately separate from activeJob: the countdown guards the steady TORCH mode
    // and must never join the strobe/SOS exclusivity chain. Main thread only.
    private var autoOffJob: Job? = null

    private var activeJob: Job? = null

    init {
        viewModelScope.launch(torchDispatcher) {
            cameraId = resolveFlashCameraId()
            // Explicit main handler: the torch thread has no Looper, and this keeps
            // callback delivery on the main thread
            cameraManager?.registerTorchCallback(torchCallback, Handler(Looper.getMainLooper()))
        }
    }

    fun selectTab(mode: TorchMode) {
        if (_selectedTab.value == mode) return
        stopAll()
        _selectedTab.value = mode
    }

    fun toggleTorch() {
        // Decided synchronously on main so a fast double-tap still reads its own write
        val turnOn = !lastKnownTorchOn
        stopAll()
        if (!turnOn) return
        // Optimistic; corrected below if the hardware call fails
        lastKnownTorchOn = true
        val previous = activeJob
        activeJob = viewModelScope.launch {
            // Joining first means a cancelled loop's finally (torch off) always lands
            // before — and so can never extinguish — this turn-on
            previous?.cancelAndJoin()
            if (setTorchHardware(true)) {
                scheduleAutoOff()
            } else {
                lastKnownTorchOn = false
                // A setAutoOff picked while this turn-on was in flight saw the
                // optimistic lastKnownTorchOn and armed a countdown; the light it
                // would guard never came on
                cancelAutoOff()
            }
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
            TorchPrefs.saveStrobeRate(application, clamped)
        }
    }

    fun setAutoOff(setting: AutoOffSetting) {
        if (_autoOffSetting.value != setting) {
            _autoOffSetting.value = setting
            TorchPrefs.saveAutoOffSetting(application, setting)
        }
        // Always reschedule: re-picking the current duration restarts its countdown
        // from now. No-ops while the torch is off.
        scheduleAutoOff()
    }

    /** Main thread. (Re)starts the countdown for the current setting, if the torch is on. */
    private fun scheduleAutoOff() {
        cancelAutoOff()
        if (!lastKnownTorchOn || _selectedTab.value != TorchMode.TORCH) return
        val remainingMs: () -> Long = when (val setting = _autoOffSetting.value ?: AutoOffSetting.Never) {
            AutoOffSetting.Never -> return
            is AutoOffSetting.AfterMinutes -> {
                // elapsedRealtime: a wall-clock edit mid-countdown can't stretch it
                val deadline = SystemClock.elapsedRealtime() + setting.minutes * 60_000L
                ({ deadline - SystemClock.elapsedRealtime() })
            }
            is AutoOffSetting.AtTime -> {
                // Wall clock on purpose: a manual clock edit moves the remaining time.
                // A timezone change does not — the timer keeps the originally
                // scheduled instant
                val deadline = nextOccurrenceEpochMs(setting.hour, setting.minute)
                ({ deadline - System.currentTimeMillis() })
            }
        }
        autoOffJob = viewModelScope.launch {
            while (true) {
                // Remaining is recomputed from the deadline each tick, so the countdown
                // can't drift, and a process the OS froze fires immediately on thaw.
                // (A guaranteed on-time fire while frozen would need AlarmManager plus a
                // foreground service; out of proportion for this app.)
                val left = remainingMs()
                if (left <= 0) break
                _autoOffRemainingSecs.value = (left + 999) / 1000  // ceil: opens on "5:00", never shows "0:00"
                delay(minOf(1_000L, left))
            }
            _autoOffRemainingSecs.value = null
            // stopAll cancels this very job, which is safe: cancellation only lands at a
            // suspension point and nothing suspends after this call
            stopAll()
        }
    }

    /** Main thread. Kills the running countdown; the persisted setting is untouched. */
    private fun cancelAutoOff() {
        autoOffJob?.cancel()
        autoOffJob = null
        if (_autoOffRemainingSecs.value != null) {
            _autoOffRemainingSecs.value = null
        }
    }

    fun stopAll() {
        // Any running auto-off countdown dies with the light it was guarding
        cancelAutoOff()
        // Cancel but keep the reference: launchExclusive must still be able to join the
        // old job so its finally can't extinguish the next mode's first flash
        activeJob?.cancel()
        // Raw enqueue rather than a viewModelScope coroutine: this must still run when
        // called from onDestroy moments before the scope is cancelled
        try {
            torchExecutor.execute { setTorchInternal(false) }
        } catch (e: RejectedExecutionException) {
            // Executor already shut down: onCleared runs BEFORE the activity's onDestroy
            // body, and its own stopAll turned the torch off
        }
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
                withContext(torchDispatcher, block)
            } finally {
                // NonCancellable: the job is usually already cancelled here, and a plain
                // withContext would throw before running its block. It must wrap the
                // dispatcher hop as a NESTED withContext — combined as one context, the
                // hop resumes onto the cancelled outer job and throws on return, which
                // would skip the _activeMode reset and strand the UI in the running state.
                // The off stays unconditional as a safety net; it can't extinguish a later
                // turn-on because every turn-on joins this job first, and hardware calls
                // are FIFO on the torch thread.
                withContext(NonCancellable) {
                    withContext(torchDispatcher) { setTorchInternal(false) }
                    if (_activeMode.value == mode) {
                        _activeMode.value = null
                    }
                }
            }
        }
    }

    /** Runs the blocking hardware call on the torch thread. */
    private suspend fun setTorchHardware(on: Boolean): Boolean =
        withContext(torchDispatcher) { setTorchInternal(on) }

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

    private fun resolveFlashCameraId(): String? {
        val manager = cameraManager ?: return null
        val ids = try {
            manager.cameraIdList
        } catch (e: CameraAccessException) {
            e.printStackTrace()
            return null
        }
        return ids.firstOrNull { id ->
            try {
                manager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } catch (e: CameraAccessException) {
                e.printStackTrace()
                false
            } catch (e: IllegalArgumentException) {
                // The id can vanish between cameraIdList and this query; skip it
                e.printStackTrace()
                false
            }
        }
    }

    override fun onCleared() {
        // viewModelScope is already cancelled when this runs, so cleanup goes straight
        // through the executor instead of a coroutine
        stopAll()
        torchExecutor.execute { cameraManager?.unregisterTorchCallback(torchCallback) }
        // shutdown(): drains the queue, then the thread exits; never blocks this thread
        torchDispatcher.close()
        super.onCleared()
    }
}
