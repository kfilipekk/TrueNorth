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
import androidx.preference.PreferenceManager
import com.google.android.gms.location.*
import com.google.android.material.button.MaterialButton
import com.truenorth.app.databinding.ActivityMainBinding
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

class MainActivity : AppCompatActivity(), FusionListener, SensorEventListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var engine: SensorFusionEngine
    private lateinit var sensorManager: SensorManager
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var vibrator: Vibrator
    
    private var posMarker: Marker? = null
    private val predictedPathLine = Polyline()
    private val actualPathLine = Polyline()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        //osmdroid config
        Configuration.getInstance().userAgentValue = packageName
        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this))
        
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupMap()
        engine = SensorFusionEngine(this)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        
        vibrator = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        setupSimControls()
        binding.btnForceStart.setOnClickListener {
            onLog(LogEntry(System.currentTimeMillis(), "GPS: Manual initialisation", LogLevel.WARN))
            val fallback = Location(LocationManager.GPS_PROVIDER).apply {
                latitude = 37.421998; longitude = -122.084000; altitude = 0.0; accuracy = 10f; time = System.currentTimeMillis()
            }
            engine.onGpsLocation(fallback, 0)
        }
        checkPermissions()
    }

    private fun setupMap() {
        binding.map.setTileSource(TileSourceFactory.MAPNIK)
        binding.map.setMultiTouchControls(true)
        binding.map.controller.setZoom(18.0)
        
        //predicted path (truenorth) - cyan
        predictedPathLine.outlinePaint.color = android.graphics.Color.parseColor("#00D4FF")
        predictedPathLine.outlinePaint.strokeWidth = 6f
        binding.map.overlays.add(predictedPathLine)
        
        //actual path (gps) - magenta
        actualPathLine.outlinePaint.color = android.graphics.Color.parseColor("#FF00FF")
        actualPathLine.outlinePaint.strokeWidth = 4f
        actualPathLine.outlinePaint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(10f, 10f), 0f)
        binding.map.overlays.add(actualPathLine)
    }

    private fun setupSimControls() {
        val btn = binding.btnJamming as MaterialButton
        btn.setOnClickListener {
            vibrate(50) //tactical feedback
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
    }

    private fun checkPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.BODY_SENSORS,
            Manifest.permission.ACTIVITY_RECOGNITION,
            Manifest.permission.READ_PHONE_STATE
        )
        
        //background location for long walks
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            permissions.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }

        if (permissions.any { ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }) {
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 1)
        } else {
            startNavigation()
        }
    }

    private fun startNavigation() {
        engine.start()
        
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST)
            onLog(LogEntry(System.currentTimeMillis(), "IMU: Accelerometer initialised", LogLevel.SUCCESS))
        } ?: onLog(LogEntry(System.currentTimeMillis(), "IMU: Accelerometer MISSING", LogLevel.ERROR))

        sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST)
            onLog(LogEntry(System.currentTimeMillis(), "IMU: Gyroscope initialised", LogLevel.SUCCESS))
        } ?: onLog(LogEntry(System.currentTimeMillis(), "IMU: Gyroscope MISSING", LogLevel.WARN))

        sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST)
        } ?: onLog(LogEntry(System.currentTimeMillis(), "IMU: Magnetometer MISSING", LogLevel.WARN))

        sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
            onLog(LogEntry(System.currentTimeMillis(), "BARO: Barometer initialised", LogLevel.SUCCESS))
        } ?: onLog(LogEntry(System.currentTimeMillis(), "BARO: Barometer MISSING", LogLevel.WARN))

        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L).build()
        val locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { engine.onGpsLocation(it, 0) }
            }
        }

        try {
            fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
                loc?.let {
                    onLog(LogEntry(System.currentTimeMillis(), "GPS: Initialised from Fused cache", LogLevel.SUCCESS))
                    engine.onGpsLocation(it, 0)
                }
            }
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
            onLog(LogEntry(System.currentTimeMillis(), "GPS: Requesting Google Fused Fix", LogLevel.INFO))
        } catch (e: SecurityException) {
            onLog(LogEntry(System.currentTimeMillis(), "GPS Permission denied", LogLevel.ERROR))
        }
    }

    private fun vibrate(durationMs: Long) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(durationMs)
        }
    }

    override fun onTelemetryUpdate(data: TelemetryData) {
        binding.navigationMap.updateTelemetry(data)
        
        runOnUiThread {
            //show force start button if no fix
            if (data.northingM == 0.0 && data.eastingM == 0.0) {
                binding.btnForceStart.visibility = android.view.View.VISIBLE
            } else {
                binding.btnForceStart.visibility = android.view.View.GONE
            }

            val (lat, lon) = engine.toGlobal(data.northingM, data.eastingM)
            binding.txtCoords.text = "LAT: %.6f LON: %.6f".format(lat, lon)
            binding.txtAlt.text = "ALT: %.1fm (Δ %.1fm)".format(data.altitudeMSL, data.altitudeDeltaM)
            binding.txtMode.text = "MODE: ${data.mode.displayName}"
            binding.txtMode.setTextColor(data.mode.color)
            binding.txtUncertainty.text = "UNCERTAINTY: %.1fm".format(data.positionUncertaintyM)
            
            //ekf diagnostics
            val x = data.ekfStateX
            binding.txtEkfState.text = "N: %.1f E: %.1f\nA: %.1f H: %.1f\nV: %.2f".format(x[0], x[1], x[2], Math.toDegrees(x[3]), x[4])
            
            val p = data.ekfCovP
            binding.txtEkfCov.text = "pos: ±%.1fm\nalt: ±%.1fm\nhdg: ±%.1f°\nspd: ±%.2fm/s".format(
                Math.sqrt(p[0] + p[1]), Math.sqrt(p[2]), Math.toDegrees(Math.sqrt(p[3])), Math.sqrt(p[4])
            )
            
            //sensor status hud updates
            updateSensorStatus(data)
            
            val gp = GeoPoint(lat, lon)
            if (posMarker == null) {
                posMarker = Marker(binding.map)
                posMarker?.title = "TrueNorth"
                posMarker?.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                binding.map.overlays.add(posMarker)
            }
            posMarker?.position = gp
            binding.map.controller.animateTo(gp)
        }
    }

    private fun updateSensorStatus(data: TelemetryData) {
        val okColor = android.graphics.Color.parseColor("#00FF88")
        val warnColor = android.graphics.Color.parseColor("#FFB300")
        val errorColor = android.graphics.Color.RED

        //imu status: ready if gyro/mag are alive
        if (data.confidence.imu > 0.1) {
            binding.statusImu.text = "IMU [ACTIVE]"
            binding.statusImu.setTextColor(okColor)
        } else {
            binding.statusImu.text = "IMU [READY]"
            binding.statusImu.setTextColor(okColor)
        }

        //baro status
        if (data.confidence.barometer > 0.5) {
            binding.statusBaro.text = "BARO [LOCK]"
            binding.statusBaro.setTextColor(okColor)
        } else {
            binding.statusBaro.text = "BARO [OFF]"
            binding.statusBaro.setTextColor(errorColor)
        }

        //cell status
        if (data.visibleCells > 0) {
            binding.statusCell.text = "CELL [${data.visibleCells} TWR]"
            binding.statusCell.setTextColor(if (data.confidence.cellDoppler > 0.1) okColor else warnColor)
        } else {
            binding.statusCell.text = "CELL [NO SIG]"
            binding.statusCell.setTextColor(errorColor)
        }
    }

    override fun onPathPoint(point: PathPoint) {
        binding.navigationMap.addPathPoint(point)
        runOnUiThread {
            val (lat, lon) = engine.toGlobal(point.northM, point.eastM)
            val gp = GeoPoint(lat, lon)
            if (point.isGpsActual) {
                actualPathLine.addPoint(gp)
            } else {
                predictedPathLine.addPoint(gp)
            }
            binding.map.invalidate()
        }
    }

    override fun onModeChange(newMode: NavigationMode, reason: String) {
        //handled by telemetryupdate and mapview
    }

    override fun onLog(entry: LogEntry) {
        runOnUiThread {
            val currentText = binding.logView.text.toString()
            val newText = "[${entry.level}] ${entry.message}\n$currentText"
            binding.logView.text = newText.take(500)
        }
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

    override fun onResume() {
        super.onResume()
        binding.map.onResume()
    }

    override fun onPause() {
        super.onPause()
        binding.map.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        engine.stop()
        sensorManager.unregisterListener(this)
    }
}
