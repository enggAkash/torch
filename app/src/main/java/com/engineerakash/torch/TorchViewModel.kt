package com.engineerakash.torch

import android.app.Application
import android.content.Context
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraManager
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData

class TorchViewModel(private val application: Application) : AndroidViewModel(application) {
    private val cameraManager: CameraManager? =
        application.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
    private var cameraId: String? = null
    var isTorchOn: MutableLiveData<Boolean> = MutableLiveData()

    init {
        try {
            cameraId = cameraManager?.cameraIdList?.get(0) // Usually, the back camera is at index 0
        } catch (e: CameraAccessException) {
            e.printStackTrace()
            Toast.makeText(
                application,
                application.getString(R.string.something_is_not_right), Toast.LENGTH_SHORT
            ).show()
        }

        cameraManager?.registerTorchCallback(object : CameraManager.TorchCallback() {
            override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                this@TorchViewModel.isTorchOn.postValue(enabled)
            }
        }, null)
    }

    fun turnOnTorch(isTorchOn: Boolean) {

        if (cameraManager != null && cameraId != null) {
            try {
                cameraManager.setTorchMode(cameraId!!, isTorchOn)

            } catch (e: CameraAccessException) {
                e.printStackTrace()
                application.getString(R.string.something_is_not_right)
            }
        }
    }
}