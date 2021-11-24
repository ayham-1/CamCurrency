package com.ayham.camcurrency

import com.ayham.libcamcurrency.CamTools

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.core.app.ActivityCompat
import java.util.concurrent.Executors
import androidx.camera.core.Camera
import androidx.camera.view.PreviewView

import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdView

class MainActivity : AppCompatActivity() {
    private val TAG = "CamCurrency"
    private lateinit var camtools: CamTools

    private var camera: Camera? = null

    lateinit var mAdView : AdView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        MobileAds.initialize(this) {}
        mAdView = findViewById(R.id.adView)
        val adRequest = AdRequest.Builder().build()
        mAdView.loadAd(adRequest)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // setup camtools
        this.camtools = CamTools(this,
            this,
            findViewById<PreviewView>(R.id.viewFinder),
            findViewById<SurfaceView>(R.id.overlay),
            findViewById<TextView>(R.id.convertedOutputText),
            findViewById<TextView>(R.id.convertedInputText),
            this,
            lifecycle,
            this.camera,
            Executors.newSingleThreadExecutor(),
            getString(R.string.overlay_help))

        // Populate SHPINNE
        val spinner: Spinner = findViewById(R.id.operatorSpinner)
        ArrayAdapter.createFromResource(
            this,
            R.array.operatorSpinner,
            android.R.layout.simple_spinner_item
        ).also { adapter ->
            // Specify the layout to use when the list of choices appears
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            // Apply the adapter to the spinner
            spinner.adapter = adapter
        }

        // Request camera permissions
        if (camtools.allPermissionsGranted()) {
            camtools.startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, CamTools.REQUIRED_PERMISSIONS, CamTools.REQUEST_CODE_PERMISSIONS)
        }

        camtools.draw_overlay()
    }

    fun onSetBtn(view: View) {
        // Read SHPINNE value
        val operatorSpinner = findViewById<Spinner>(R.id.operatorSpinner)
        camtools.is_multiply = operatorSpinner.getSelectedItem().toString() == "Multiply";

        // Read EditText Value
        val valueNumberDecimal = findViewById<EditText>(R.id.valueNumberDecimal)
        val temp = valueNumberDecimal.getText().toString().toDoubleOrNull()
        if (temp == null)
            Toast.makeText(this, "Enter a decimal number!",
                Toast.LENGTH_SHORT).show()
        else camtools.value = temp
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults:
        IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CamTools.REQUEST_CODE_PERMISSIONS) {
            if (camtools.allPermissionsGranted()) {
                camtools.startCamera()
            } else {
                Toast.makeText(this,
                    "Permissions not granted by the user.",
                    Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        camtools.cameraExecutor.shutdown()
    }

}