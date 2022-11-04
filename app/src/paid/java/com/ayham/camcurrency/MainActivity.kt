package com.ayham.camcurrency

import android.annotation.SuppressLint
import com.ayham.libcamcurrency.CamTools
import com.ayham.camcurrency.FloatRate

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.core.app.ActivityCompat
import java.util.concurrent.Executors
import androidx.camera.core.Camera
import androidx.camera.view.PreviewView
import android.app.ProgressDialog
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Message
import java.lang.Exception
import android.widget.ArrayAdapter
import android.telephony.TelephonyManager
import java.util.*
import kotlin.collections.ArrayList

class MainActivity : AppCompatActivity() {
    private lateinit var camtools: CamTools
    private lateinit var floatrate: FloatRate

    private var camera: Camera? = null

    private lateinit var progressBar: RelativeLayout
    private lateinit var mainContent: LinearLayout
    private lateinit var progressUiHandler: Handler

    private lateinit var fromCurrencySpinner: Spinner
    private lateinit var toCurrencySpinner: Spinner
    private var fromCurrencyRate: Double = 0.0
    private var fromCurrencyCode: String = ""
    private var toCurrencyRate: Double = 0.0
    private var toCurrencyCode: String = ""
    private lateinit var currencyRateHandler: Handler

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        this.progressBar = findViewById(R.id.progressUi)
        this.mainContent = findViewById(R.id.mainContent)
        this.fromCurrencySpinner = findViewById(R.id.fromCurrencySpinner)
        this.toCurrencySpinner = findViewById(R.id.toCurrencySpinner)
        progressBar.visibility = View.VISIBLE

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // setup camtools
        this.camtools = CamTools(
            this,
            this,
            findViewById<PreviewView>(R.id.viewFinder),
            findViewById<SurfaceView>(R.id.overlay),
            findViewById<TextView>(R.id.convertedOutputText),
            findViewById<TextView>(R.id.convertedInputText),
            this,
            lifecycle,
            this.camera,
            Executors.newSingleThreadExecutor(),
            getString(R.string.overlay_help)
        )

        // Request camera permissions
        if (!camtools.allPermissionsGranted()) {
            ActivityCompat.requestPermissions(
                this, CamTools.REQUIRED_PERMISSIONS, CamTools.REQUEST_CODE_PERMISSIONS
            )
        }

        this.fromCurrencySpinner.onItemSelectedListener =  object : AdapterView.OnItemSelectedListener{
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedItem: String = parent?.getItemAtPosition(position).toString()

                val currencyName = selectedItem.split("(")[0]
                val currencyCode = selectedItem.split("(")[1]
                    .replace("(", "").replace(")", "")

                val mainThis = this@MainActivity
                try {
                    mainThis.fromCurrencyRate =
                        mainThis.floatrate.ratesDao.findByTargetCurrency(currencyCode).inverseRate
                    mainThis.fromCurrencyCode = currencyCode

                    mainThis.currencyRateHandler.sendEmptyMessage(0)
                } catch (e: Exception) {
                    return
                }
            }

            override fun onNothingSelected(p0: AdapterView<*>?) {
            }
        }
        this.toCurrencySpinner.onItemSelectedListener =  object : AdapterView.OnItemSelectedListener{
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedItem: String = parent?.getItemAtPosition(position).toString()

                val currencyName = selectedItem.split("(")[0]
                val currencyCode = selectedItem.split("(")[1]
                    .replace("(", "").replace(")", "")

                val mainThis = this@MainActivity
                try {
                    mainThis.toCurrencyRate = mainThis.floatrate.ratesDao.findByTargetCurrency(currencyCode).exchangeRate
                    mainThis.toCurrencyCode = currencyCode
                    mainThis.currencyRateHandler.sendEmptyMessage(0)
                } catch (e: Exception) {
                    return
                }
            }

            override fun onNothingSelected(p0: AdapterView<*>?) {
            }
        }
    }

    override fun onStart() {
        super.onStart()

        var pool = Executors.newFixedThreadPool(1)
        pool.execute {
            this.floatrate = FloatRate(this)
            this.progressUiHandler.sendEmptyMessage(View.VISIBLE)
        }
    }

    override fun onResume() {
        super.onResume()

        @SuppressLint("HandlerLeak")
        this.currencyRateHandler = object : Handler() {
            override fun handleMessage(msg: Message) {
                val mainThis = this@MainActivity
                if (mainThis.fromCurrencyCode == mainThis.toCurrencyCode) mainThis.camtools.value = 1.0
                else mainThis.camtools.value = mainThis.fromCurrencyRate * mainThis.toCurrencyRate
            }
        }

        @SuppressLint("HandlerLeak")
        this.progressUiHandler = object : Handler() {
            override fun handleMessage(msg: Message) {
                super.handleMessage(msg)
                progressBar.visibility = View.GONE
                mainContent.visibility = msg.what
                camtools.startCamera()
                camtools.draw_overlay()

                // Check if rates are empty
                if (floatrate.ratesDao.getAll().size == 0) {
                    Toast.makeText(this@MainActivity,
                        "Could not retrieve currencies, trying again...", Toast.LENGTH_LONG).show()
                    this@MainActivity.onStart() // initialize floatrates AGAIN
                    return
                }

                // Populate SHPINNE
                val currencies: ArrayList<String> = ArrayList<String>()
                currencies.add("US Dollar (USD)") // add the dollar as it is the base
                for (item in floatrate.ratesDao.getAll().sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, { it.targetName.toString() }))) {
                    val displayName = item.targetName + " (" + item.targetCurrency + ")"
                    currencies.add(displayName)
                }
                val adapter: ArrayAdapter<*> =
                    ArrayAdapter<String>(this@MainActivity, android.R.layout.simple_spinner_item, currencies)
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                fromCurrencySpinner.adapter = adapter
                toCurrencySpinner.adapter = adapter

                try {
                    // Try to predict country to convert to
                    val locale = this@MainActivity.applicationContext.resources.configuration.locale
                    val currencyTo: Currency = Currency.getInstance(locale)
                    toCurrencySpinner.setSelection(currencies.indexOf(
                        currencies.first { elem -> elem == currencyTo.displayName + " (" + currencyTo.currencyCode + ")" }
                    ))

                    // Try to predict country to convert from
                    val tm: TelephonyManager =
                        getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                    val currencyFrom: Currency =
                        Currency.getInstance(Locale("", tm.networkCountryIso))
                    fromCurrencySpinner.setSelection(currencies.indexOf(
                        currencies.first { elem -> elem == currencyFrom.displayName + " (" + currencyFrom.currencyCode + ")" }
                    ))
                } catch (e: Exception) { }
            }
        }
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