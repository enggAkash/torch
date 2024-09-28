package com.engineerakash.torch

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class MainActivity : AppCompatActivity() {
    private val torchUtil by lazy {
        TorchUtil(this)
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

        brightNessIv.setOnClickListener {
            torchClicked(rootLayout, brightNessIv)
        }

        torchIv.setOnClickListener {
            torchClicked(rootLayout, brightNessIv)
        }

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