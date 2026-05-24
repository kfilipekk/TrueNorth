package com.truenorth.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationManager
import android.os.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.preference.PreferenceManager
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.google.android.gms.location.*
import com.google.android.material.button.MaterialButton
import com.truenorth.app.databinding.ActivityMainBinding
import org.osmdroid.config.Configuration

class MainActivity : AppCompatActivity(), FusionListener, SensorEventListener, MapFragment.MapInteractionListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var engine: SensorFusionEngine
    private lateinit var sensorManager: SensorManager
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var vibrator: Vibrator
    private lateinit var viewModel: TrueNorthViewModel
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        viewModel = ViewModelProvider(this)[TrueNorthViewModel::class.java]
        
        Configuration.getInstance().userAgentValue = packageName
        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this))
        
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupViewPager()
        
        engine = SensorFusionEngine(this)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        checkPermissions()
    }

    private fun setupViewPager() {
        binding.viewPager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount(): Int = 5
            override fun createFragment(position: Int): Fragment {
                return when (position) {
                    0 -> MapFragment()
                    1 -> AnalyticsFragment()
                    2 -> RadioFragment()
                    3 -> LiveLogFragment()
                    else -> InfoFragment()
                }
            }
        }
        binding.viewPager.isUserInputEnabled = false
        binding.viewPager.offscreenPageLimit = 4 // Keep all tabs alive to prevent re-creation crashes
        
        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_map -> binding.viewPager.setCurrentItem(0, false)
                R.id.nav_analytics -> binding.viewPager.setCurrentItem(1, false)
                R.id.nav_radio -> binding.viewPager.setCurrentItem(2, false)
                R.id.nav_log -> binding.viewPager.setCurrentItem(3, false)
                R.id.nav_info -> binding.viewPager.setCurrentItem(4, false)
            }
            true
        }
    }

    override fun onJammingClicked() {
        vibrate(50)
        val mapFrag = supportFragmentManager.fragments.filterIsInstance<MapFragment>().firstOrNull() ?: return
        val btn = mapFrag.binding.btnJamming as MaterialButton
        if (engine.demoActive) {
            engine.deactivateDemoMode()
            btn.text = "JAM GPS"
            btn.setIconResource(R.drawable.ic_jamming_off)
            btn.iconTint = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#00FF88"))
            btn.setTextColor(android.graphics.Color.parseColor("#00FF88"))
        } else {
            engine.activateDemoMode()
            btn.text = "STOP JAM"
            btn.setIconResource(R.drawable.ic_jamming_on)
            btn.iconTint = android.content.res.ColorStateList.valueOf(android.graphics.Color.RED)
            btn.setTextColor(android.graphics.Color.RED)
        }
    }

    override fun onSpoofingClicked() {
        vibrate(100) //heavier tactile for alert
        val mapFrag = supportFragmentManager.fragments.filterIsInstance<MapFragment>().firstOrNull() ?: return
        val btn = mapFrag.binding.btnSpoofing
        if (engine.spoofingActive) {
            engine.deactivateSpoofingSimulation()
            btn.text = "SIM SPOOF"
            btn.setTextColor(android.graphics.Color.parseColor("#FF4444"))
        } else {
            engine.activateSpoofingSimulation()
            btn.text = "STOP SPOOF"
            btn.setTextColor(android.graphics.Color.YELLOW)
        }
    }

    override fun onForceStartClicked() {
        onLog(LogEntry(System.currentTimeMillis(), "GPS: Manual initialisation", LogLevel.WARN))
        val fallback = Location(LocationManager.GPS_PROVIDER).apply {
            latitude = 37.421998; longitude = -122.084000; altitude = 0.0; accuracy = 10f; time = System.currentTimeMillis()
        }
        engine.onGpsLocation(fallback, 0)
    }

    private fun checkPermissions() {
        val basePermissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.BODY_SENSORS,
            Manifest.permission.ACTIVITY_RECOGNITION,
            Manifest.permission.READ_PHONE_STATE
        )
        
        // Wifi permissions are not runtime on all versions but good to have
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            basePermissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }

        val missing = basePermissions.filter { 
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED 
        }

        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 100)
        } else {
            checkBackgroundLocation()
        }
    }

    private fun checkBackgroundLocation() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                // Background location must be requested separately on Android 11+
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), 101)
                return
            }
        }
        startNavigation()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                checkBackgroundLocation()
            } else {
                onLog(LogEntry(System.currentTimeMillis(), "Permissions: Core permissions denied. Radio/GPS may fail.", LogLevel.ERROR))
                // start anyway, try to recover
                checkBackgroundLocation()
            }
        } else if (requestCode == 101) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                onLog(LogEntry(System.currentTimeMillis(), "Permissions: Background access granted", LogLevel.SUCCESS))
            } else {
                onLog(LogEntry(System.currentTimeMillis(), "Permissions: Background denied. EFB will stop when screen off.", LogLevel.WARN))
            }
            startNavigation()
        }
    }

    private fun startNavigation() {
        engine.start()
        
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST)
            onLog(LogEntry(System.currentTimeMillis(), "IMU: Accelerometer initialised", LogLevel.SUCCESS))
        }

        sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST)
            onLog(LogEntry(System.currentTimeMillis(), "IMU: Gyroscope initialised", LogLevel.SUCCESS))
        }

        sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST)
        }

        sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }

        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L).build()
        val locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { engine.onGpsLocation(it, 0) }
            }
        }

        try {
            fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
                loc?.let { engine.onGpsLocation(it, 0) }
            }
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
        } catch (e: SecurityException) {}
    }

    private fun vibrate(durationMs: Long) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(durationMs)
        }
    }

    override fun onTelemetryUpdate(data: TelemetryData) {
        viewModel.updateTelemetry(data)
    }

    override fun onPathPoint(point: PathPoint) {
        viewModel.addPathPoint(point)
    }

    override fun onModeChange(newMode: NavigationMode, reason: String) {}

    override fun onLog(entry: LogEntry) {
        viewModel.addLog(entry)
    }

    override fun onStepDetected() {
        runOnUiThread { vibrate(10) }
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> engine.onAccelerometer(event.values, event.timestamp)
            Sensor.TYPE_GYROSCOPE -> engine.onGyroscope(event.values)
            Sensor.TYPE_MAGNETIC_FIELD -> engine.onMagnetometer(event.values)
            Sensor.TYPE_PRESSURE -> engine.onBarometer(event.values[0])
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onDestroy() {
        super.onDestroy()
        engine.stop()
        sensorManager.unregisterListener(this)
    }
}
