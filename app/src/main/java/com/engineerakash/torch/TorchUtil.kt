package com.engineerakash.torch

import android.content.Context
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraManager
import android.widget.Toast
import androidx.lifecycle.MutableLiveData

class TorchUtil(private val context: Context) {
    private val cameraManager: CameraManager? =
        context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
    private var cameraId: String? = null
    var isTorchOn: MutableLiveData<Boolean> =  MutableLiveData()

    init {
        try {
            cameraId = cameraManager?.cameraIdList?.get(0) // Usually, the back camera is at index 0
        } catch (e: CameraAccessException) {
            e.printStackTrace()
            Toast.makeText(
                context,
                context.getString(R.string.something_is_not_right), Toast.LENGTH_SHORT
            ).show()
        }

        cameraManager?.registerTorchCallback(object : CameraManager.TorchCallback() {
            override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                this@TorchUtil.isTorchOn.postValue(enabled)
            }
        }, null)
    }

    fun turnOnTorch(isTorchOn: Boolean) {

        if (cameraManager != null && cameraId != null) {
            try {
                cameraManager.setTorchMode(cameraId!!, isTorchOn)

            } catch (e: CameraAccessException) {
                e.printStackTrace()
                context.getString(R.string.something_is_not_right)
            }
        }
    }
}