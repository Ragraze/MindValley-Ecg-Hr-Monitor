package com.polar.polarsdkecghrdemo

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.util.Log
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.androidplot.xy.BoundaryMode
import com.androidplot.xy.StepMode
import com.androidplot.xy.XYGraphWidget
import com.androidplot.xy.XYPlot
import com.google.android.material.navigation.NavigationView
import com.polar.sdk.api.PolarBleApi
import com.polar.sdk.api.PolarBleApiCallback
import com.polar.sdk.api.PolarBleApiDefaultImpl.defaultImplementation
import com.polar.sdk.api.errors.PolarInvalidArgument
import com.polar.sdk.api.model.PolarDeviceInfo
import com.polar.sdk.api.model.PolarHrData
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.disposables.Disposable
import java.text.DecimalFormat
import java.util.*

class TrackerActivity : AppCompatActivity(), PlotterListener {
    companion object {
        private const val TAG = "TrackerActivity"
    }

    private lateinit var api: PolarBleApi
    private lateinit var plotter: HrAndRrPlotter
    private lateinit var textViewHR: TextView
    private lateinit var textViewRR: TextView
    private lateinit var textViewDeviceId: TextView
    private lateinit var textViewBattery: TextView
    private lateinit var textViewFwVersion: TextView
    private lateinit var plot: XYPlot
    private var hrDisposable: Disposable? = null

    private lateinit var deviceId: String

    private val moodImages = intArrayOf(
        R.drawable.happy,
        R.drawable.sad,
        R.drawable.angry,
    )
    private var currentMoodIndex = 0

    private val handler = Handler()
    private lateinit var imageChangeRunnable: Runnable

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tracker)
        deviceId = intent.getStringExtra("id") ?: throw Exception("Tracker couldn't be created, no deviceId given")

        plot = findViewById(R.id.hr_view_plot)

        api = defaultImplementation(
            applicationContext,
            setOf(
                PolarBleApi.PolarBleSdkFeature.FEATURE_POLAR_ONLINE_STREAMING,
                PolarBleApi.PolarBleSdkFeature.FEATURE_BATTERY_INFO,
                PolarBleApi.PolarBleSdkFeature.FEATURE_DEVICE_INFO
            )
        )

        plotter = HrAndRrPlotter()
        plotter.setListener(this)
        plot.addSeries(plotter.hrSeries, plotter.hrFormatter)
        plot.addSeries(plotter.rrSeries, plotter.rrFormatter)
        plot.setRangeBoundaries(50, 100, BoundaryMode.AUTO)
        plot.setDomainBoundaries(0, 360000, BoundaryMode.AUTO)
        plot.setRangeStep(StepMode.INCREMENT_BY_VAL, 10.0)
        plot.setDomainStep(StepMode.INCREMENT_BY_VAL, 60000.0)
        plot.graph.getLineLabelStyle(XYGraphWidget.Edge.LEFT).format = DecimalFormat("#")
        plot.linesPerRangeLabel = 2
        api = defaultImplementation(
            applicationContext,
            setOf(
                PolarBleApi.PolarBleSdkFeature.FEATURE_POLAR_ONLINE_STREAMING,
                PolarBleApi.PolarBleSdkFeature.FEATURE_BATTERY_INFO,
                PolarBleApi.PolarBleSdkFeature.FEATURE_DEVICE_INFO
            )
        )
        api.setApiLogger { str: String -> Log.d("SDK", str) }
        api.setApiCallback(object : PolarBleApiCallback() {
            override fun blePowerStateChanged(powered: Boolean) {
                Log.d(TAG, "BluetoothStateChanged $powered")
            }

            override fun deviceConnected(polarDeviceInfo: PolarDeviceInfo) {
                Log.d(TAG, "Device connected ${polarDeviceInfo.deviceId}")
                Toast.makeText(applicationContext, R.string.connected, Toast.LENGTH_SHORT).show()
            }

            override fun deviceConnecting(polarDeviceInfo: PolarDeviceInfo) {
                Log.d(TAG, "Device connecting ${polarDeviceInfo.deviceId}")
            }

            override fun deviceDisconnected(polarDeviceInfo: PolarDeviceInfo) {
                Log.d(TAG, "Device disconnected ${polarDeviceInfo.deviceId}")
            }

            override fun bleSdkFeatureReady(identifier: String, feature: PolarBleApi.PolarBleSdkFeature) {
                Log.d(TAG, "feature ready $feature")

                when (feature) {
                    PolarBleApi.PolarBleSdkFeature.FEATURE_POLAR_ONLINE_STREAMING -> {
                        streamHR()
                    }
                    else -> {}
                }
            }

            override fun disInformationReceived(identifier: String, uuid: UUID, value: String) {
                if (uuid == UUID.fromString("00002a28-0000-1000-8000-00805f9b34fb")) {
                    val msg = "Firmware: " + value.trim { it <= ' ' }
                    Log.d(TAG, "Firmware: " + identifier + " " + value.trim { it <= ' ' })
                    textViewFwVersion.append(msg.trimIndent())
                }
            }

            override fun batteryLevelReceived(identifier: String, level: Int) {
                Log.d(TAG, "Battery level $identifier $level%")
                val batteryLevelText = "Battery level: $level%"
                textViewBattery.append(batteryLevelText)
            }
        })

        try {
            api.connectToDevice(deviceId)
        } catch (a: PolarInvalidArgument) {
            a.printStackTrace()
        }

        val moodText : TextView = findViewById(R.id.currentTitle)
        val buttonRetake : Button = findViewById(R.id.addButton)
        buttonRetake.setOnClickListener {
            finish()
            val intent = Intent(this, TrackerActivity::class.java)
            intent.putExtra("id", deviceId)
            startActivity(intent)
        }
        val moodRecording : TextView = findViewById(R.id.subtitleRecording)
        val moodTime : TextView = findViewById(R.id.subtitleTime)

        plotter = HrAndRrPlotter()
        plotter.setListener(this)

        handler.postDelayed({
            val highestY = plotter.getHighestYInInterval()
            Log.d(TAG, "Highest Y in the last 15 seconds: $highestY")

            handler.removeCallbacks(imageChangeRunnable)

            if(highestY <= 75){
                moodText.text = "Current Mood: Sadness"
                moodRecording.text = "Don't be so down, cheer up!"
            } else if(highestY>75 && highestY<153){
                moodText.text = "Current Mood: Happy"
                moodRecording.text = "Yay! Keep it up!"
            } else{
                moodText.text = "Current Mood: Angry"
                moodRecording.text = "Come on! Lighten up!"
            }

            buttonRetake.visibility = View.VISIBLE
            moodTime.visibility = View.INVISIBLE

        }, 15000) // 15 seconds in milliseconds

        imageChangeRunnable = object : Runnable {
            override fun run() {
                changeImage()
                handler.postDelayed(this, 1000)
            }
        }

        handler.postDelayed(imageChangeRunnable, 1000)

        val navView: NavigationView = findViewById(R.id.nav_view)
        navView.setNavigationItemSelectedListener { menuItem ->
            onNavigationItemSelected(menuItem)
            true
        }

        val thirdMenuItem = navView.menu.findItem(R.id.tracker_opt)
        thirdMenuItem.isChecked = true
    }

    private fun onNavigationItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.home_opt -> {
                val intent = Intent(this, MainActivity::class.java)
                intent.putExtra("id", deviceId)
                startActivity(intent)
                return true
            }
            R.id.hr_opt -> {
                val intent = Intent(this, HRActivity::class.java)
                intent.putExtra("id", deviceId)
                startActivity(intent)
                return true
            }
            R.id.tracker_opt -> {
                val intent = Intent(this, TrackerActivity::class.java)
                intent.putExtra("id", deviceId)
                startActivity(intent)
                return true
            }
            else -> return false
        }
    }

    private fun changeImage() {
        val moodImage: ImageView = findViewById(R.id.moodImage)
        moodImage.setImageResource(getNextMoodImageResource())
    }

    private fun getNextMoodImageResource(): Int {
        val nextImageResource = moodImages[currentMoodIndex]
        currentMoodIndex = (currentMoodIndex + 1) % moodImages.size
        return nextImageResource
    }

    public override fun onDestroy() {
        super.onDestroy()
        api.shutDown()
    }

    override fun update() {
        runOnUiThread { plot.redraw() }
    }

    fun streamHR() {
        val isDisposed = hrDisposable?.isDisposed ?: true
        if (isDisposed) {
            hrDisposable = api.startHrStreaming(deviceId)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                    { hrData: PolarHrData ->
                        for (sample in hrData.samples) {
                            Log.d(TAG, "HR ${sample.hr} RR ${sample.rrsMs}")

                            if (sample.rrsMs.isNotEmpty()) {
                                val rrText = "(${sample.rrsMs.joinToString(separator = "ms, ")}ms)"
                                textViewRR.text = rrText
                            }
                            textViewHR.text = sample.hr.toString()
                            plotter.addValues(sample)

                        }
                    },
                    { error: Throwable ->
                        Log.e(TAG, "HR stream failed. Reason $error")
                        hrDisposable = null
                    },
                    { Log.d(TAG, "HR stream complete") }
                )
        } else {
            hrDisposable?.dispose()
            hrDisposable = null
        }
    }
}