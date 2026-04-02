package com.truenorth.app

import android.hardware.SensorManager
import android.location.Location
import android.os.Handler
import android.os.Looper
import kotlin.math.*

//callback interface
interface FusionListener {
    fun onTelemetryUpdate(data: TelemetryData)
    fun onPathPoint(point: PathPoint)
    fun onModeChange(newMode: NavigationMode, reason: String)
    fun onLog(entry: LogEntry)
    fun onStepDetected()
}

//fusion engine
class SensorFusionEngine(private val listener: FusionListener) {

    //sub-systems
    private val ekf = ExtendedKalmanFilter()
    private val stepDetector = StepDetector { len, spd -> onStep(len, spd) }

    //raw sensor state
    @Volatile private var accel = FloatArray(3)
    @Volatile private var gyro  = FloatArray(3)
    @Volatile private var mag   = FloatArray(3)
    @Volatile private var pressurePa = 101325f
    @Volatile private var hasMag   = false
    @Volatile private var hasGyro  = false
    @Volatile private var hasBaro  = false
    
    //raw gps state for logging
    @Volatile private var rawGpsLat = 0.0
    @Volatile private var rawGpsLon = 0.0
    @Volatile private var rawGpsAlt = 0.0

    //gps state
    @Volatile private var gpsOriginLat = Double.NaN
    @Volatile private var gpsOriginLon = Double.NaN
    @Volatile private var gpsOriginAlt = Double.NaN
    @Volatile private var lastGpsTimeMs = 0L
    @Volatile private var lastGpsNorth  = 0.0
    @Volatile private var lastGpsEast   = 0.0
    @Volatile private var lastGpsAcc    = 999f
    @Volatile private var gpsSatCount   = 0

    private var prevGpsNorth = Double.NaN
    private var prevGpsEast  = Double.NaN

    //mode management
    var mode = NavigationMode.GPS_LOCK
        private set
    private val GPS_LOST_TIMEOUT_MS  = 8_000L
    private val GPS_DEGRAD_TIMEOUT_MS = 3_000L

    //cell monitor
    var cellEstimate = CellDopplerEstimate()

    //demo mode
    var demoActive = false
        private set
    private var demoStartMs = 0L

    //heading
    private var fusedHeading = 0.0
    private val COMP_ALPHA   = 0.97f
    private var headingInitialised = false

    //altitude
    private var initAltMSL = Double.NaN

    //imu drift
    private var imuOnlyStartMs = 0L

    //path recording
    private val pathPoints = mutableListOf<PathPoint>()
    private val gpsActualPoints = mutableListOf<PathPoint>() //track actual gps path
    private var tickCount = 0

    //update loop
    private val handler = Handler(Looper.getMainLooper())
    private val UPDATE_INTERVAL_MS = 50L
    
    private val cellMonitor = CellSignalMonitor(context = (listener as android.content.Context))
    private val dataLogger = DataLogger(context = (listener as android.content.Context))

    private val loopRunnable = object : Runnable {
        override fun run() {
            if (tickCount == 0) dataLogger.startNewSession()
            cellEstimate = cellMonitor.update()
            fusionTick()
            handler.postDelayed(this, UPDATE_INTERVAL_MS)
        }
    }

    fun start() {
        log("TrueNorth Engine v1.1 — EKF fusion initialised", LogLevel.SUCCESS)
        handler.post(loopRunnable)
    }

    fun stop() {
        handler.removeCallbacks(loopRunnable)
        log("engine stopped")
    }

    fun onAccelerometer(values: FloatArray, timestampNs: Long) {
        accel = values.clone()
        stepDetector.processSample(values[0], values[1], values[2], timestampNs / 1_000_000)
    }

    fun onGyroscope(values: FloatArray) {
        gyro = values.clone()
        hasGyro = true
    }

    fun onMagnetometer(values: FloatArray) {
        mag = values.clone()
        hasMag = true
    }

    fun onBarometer(pressureHpa: Float) {
        //android returns hPa. formula expects Pascals.
        pressurePa = pressureHpa * 100f
        hasBaro = true
        val altMSL = pressureToAltitudeMSL(pressurePa.toDouble())
        if (initAltMSL.isNaN()) {
            initAltMSL = altMSL
        }
    }

    fun onGpsLocation(location: Location, satellites: Int) {
        if (demoActive && System.currentTimeMillis() - demoStartMs > 4_000L) return
        
        rawGpsLat = location.latitude
        rawGpsLon = location.longitude
        rawGpsAlt = location.altitude

        if (gpsOriginLat.isNaN()) {
            gpsOriginLat = location.latitude
            gpsOriginLon = location.longitude
            gpsOriginAlt = location.altitude
            initAltMSL = location.altitude
            ekf.resetPosition(0.0, 0.0, location.altitude, fusedHeading)
        }

        val (n, e) = toLocal(location.latitude, location.longitude)

        if (!prevGpsNorth.isNaN()) {
            val jump = sqrt((n - prevGpsNorth).pow(2) + (e - prevGpsEast).pow(2))
            val dtSec = (location.time - lastGpsTimeMs) / 1000.0
            val impliedSpeedMps = if (dtSec > 0) jump / dtSec else 0.0
            if (impliedSpeedMps > 30.0) return
        }

        prevGpsNorth = n
        prevGpsEast  = e
        lastGpsNorth = n
        lastGpsEast  = e
        lastGpsAcc   = location.accuracy
        lastGpsTimeMs = System.currentTimeMillis()
        gpsSatCount  = satellites

        val gpsPt = PathPoint(n, e, location.altitude, location.time, mode, location.accuracy.toDouble(), true)
        gpsActualPoints.add(gpsPt)
        listener.onPathPoint(gpsPt)

        ekf.updateGps2D(n, e, location.accuracy)
    }

    fun onGpsSignalLost() {
        if (lastGpsTimeMs > 0 && mode == NavigationMode.GPS_LOCK) {
            log("gps signal weakening — truenorth standing by", LogLevel.WARN)
        }
    }

    fun activateDemoMode() {
        demoActive    = true
        demoStartMs   = System.currentTimeMillis()
        imuOnlyStartMs = System.currentTimeMillis()
        log("JAMMING DETECTED — Switching to TrueNorth Mode", LogLevel.WARN)
    }

    fun deactivateDemoMode() {
        demoActive = false
        log("JAMMING CLEARED — GPS Lock restored", LogLevel.SUCCESS)
    }

    private fun fusionTick() {
        val dt = UPDATE_INTERVAL_MS / 1000.0
        val magHeading = computeMagHeading()
        val gyroZ = if (hasGyro) gyro[2].toDouble() else 0.0

        if (!headingInitialised && magHeading != null) {
            fusedHeading = magHeading
            headingInitialised = true
            ekf.resetPosition(ekf.north, ekf.east, ekf.altitude, fusedHeading)
        } else if (headingInitialised) {
            val gyroIntegrated = fusedHeading + gyroZ * dt
            if (magHeading != null) {
                val diff = wrapAngle(magHeading - gyroIntegrated)
                fusedHeading = wrapAngle(gyroIntegrated + (1 - COMP_ALPHA) * diff)
            } else {
                fusedHeading = wrapAngle(gyroIntegrated)
            }
            ekf.updateMagnetometer(fusedHeading)
        }

        ekf.predict(dt, gyroZ)

        if (hasBaro && !initAltMSL.isNaN()) {
            val altMSL = pressureToAltitudeMSL(pressurePa.toDouble())
            ekf.updateBarometer(altMSL)
        }

        if (cellEstimate.confidence > 0.15f && cellEstimate.speedHintMps > 0.05) {
            ekf.updateCellSpeedHint(cellEstimate.speedHintMps)
        }

        updateMode()

        tickCount++
        val telemetry = buildTelemetry()
        listener.onTelemetryUpdate(telemetry)
        
        if (tickCount % 20 == 0) {
            dataLogger.log(telemetry, this)
        }

        if (tickCount % 10 == 0) {
            val pt = PathPoint(
                northM       = ekf.north,
                eastM        = ekf.east,
                altM         = ekf.altitude,
                timestamp    = System.currentTimeMillis(),
                mode         = mode,
                uncertaintyM = ekf.positionUncertaintyM()
            )
            pathPoints.add(pt)
            listener.onPathPoint(pt)
        }
    }

    private fun onStep(stepLengthM: Double, speedMps: Double) {
        ekf.updateStepSpeed(speedMps)
        listener.onStepDetected()
    }

    private fun updateMode() {
        if (demoActive) {
            val elapsed = System.currentTimeMillis() - demoStartMs
            val newMode = when {
                elapsed < 2_000  -> NavigationMode.GPS_DEGRADED
                else             -> NavigationMode.TRUENORTH
            }
            if (newMode != mode) setMode(newMode, "Jamming Simulation")
            return
        }

        val gpsAge = if (lastGpsTimeMs > 0) System.currentTimeMillis() - lastGpsTimeMs else Long.MAX_VALUE
        val newMode = when {
            gpsAge < GPS_DEGRAD_TIMEOUT_MS -> NavigationMode.GPS_LOCK
            gpsAge < GPS_LOST_TIMEOUT_MS   -> NavigationMode.GPS_DEGRADED
            else                            -> NavigationMode.TRUENORTH
        }

        if (newMode != mode) {
            setMode(newMode, "gps age ${gpsAge}ms")
            if (newMode == NavigationMode.TRUENORTH) imuOnlyStartMs = System.currentTimeMillis()
        }
    }

    private fun setMode(newMode: NavigationMode, reason: String) {
        mode = newMode
        listener.onModeChange(newMode, reason)
    }

    private fun computeConfidence(): SensorConfidence {
        val gpsAge = if (lastGpsTimeMs > 0) (System.currentTimeMillis() - lastGpsTimeMs) / 1000f else 999f
        val gpsConf = when {
            gpsAge < 3f  -> 1.0f
            gpsAge < 10f -> 1f - (gpsAge - 3f) / 7f
            else         -> 0f
        }.coerceIn(0f, 1f) * ((100f - (lastGpsAcc - 5f).coerceAtLeast(0f)) / 100f).coerceIn(0f, 1f)

        val imuDriftS = if (imuOnlyStartMs > 0) (System.currentTimeMillis() - imuOnlyStartMs) / 1000f else 0f
        
        //imu alive for ui status
        val imuAlive = if (hasGyro && hasMag) 0.95f else 0.1f
        val imuConf = if (mode == NavigationMode.GPS_LOCK) imuAlive
            else (0.98f - imuDriftS / 1200f).coerceIn(0.2f, 0.98f)

        val baroConf = if (hasBaro && pressurePa in 70_000f..110_000f) 0.90f else 0f
        val cellConf = cellEstimate.confidence

        val overall = when (mode) {
            NavigationMode.GPS_LOCK     -> gpsConf
            NavigationMode.GPS_DEGRADED -> gpsConf * 0.5f + imuConf * 0.3f + baroConf * 0.2f
            NavigationMode.TRUENORTH    -> imuConf * 0.55f + baroConf * 0.30f + cellConf * 0.15f
            NavigationMode.DEMO         -> 0.85f
        }.coerceIn(0f, 1f)

        return SensorConfidence(gpsConf, if (mode == NavigationMode.GPS_LOCK) 0f else imuConf, baroConf, cellConf, overall)
    }

    private fun buildTelemetry(): TelemetryData {
        val imuDriftMs = if (imuOnlyStartMs > 0 && mode != NavigationMode.GPS_LOCK)
            System.currentTimeMillis() - imuOnlyStartMs else 0L

        val altDelta = if (initAltMSL.isNaN()) 0.0 else ekf.altitude - initAltMSL

        return TelemetryData(
            northingM          = ekf.north,
            eastingM           = ekf.east,
            altitudeMSL        = ekf.altitude,
            altitudeDeltaM     = altDelta,
            headingDeg         = ekf.headingDeg,
            speedMps           = ekf.speed,
            stepCount          = stepDetector.stepCount(),
            pressureHPa        = pressurePa / 100.0,
            positionUncertaintyM = ekf.positionUncertaintyM(),
            headingUncertaintyDeg = ekf.headingUncertaintyDeg(),
            imuDriftMs         = imuDriftMs,
            mode               = mode,
            confidence         = computeConfidence(),
            visibleCells       = cellEstimate.visibleTowers,
            bestCellRssi       = cellEstimate.bestRssi,
            gpsAccuracyM       = lastGpsAcc,
            gpsSatellites      = gpsSatCount,
            timestamp          = System.currentTimeMillis(),
            ekfStateX          = ekf.getStateX(),
            ekfCovP            = ekf.getCovP()
        )
    }

    private fun toLocal(lat: Double, lon: Double): Pair<Double, Double> {
        val originLat = gpsOriginLat.takeIf { !it.isNaN() } ?: lat
        val originLon = gpsOriginLon.takeIf { !it.isNaN() } ?: lon
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat - originLat)
        val dLon = Math.toRadians(lon - originLon)
        val midLat = Math.toRadians((lat + originLat) / 2)
        return Pair(dLat * r, dLon * r * cos(midLat))
    }

    private fun pressureToAltitudeMSL(p: Double): Double {
        val p0 = 101325.0; val t0 = 288.15; val l = 0.0065
        val r = 8.31446; val g = 9.80665; val m = 0.028964
        return (t0 / l) * (1.0 - (p / p0).pow(r * l / (g * m)))
    }

    private fun computeMagHeading(): Double? {
        val r = FloatArray(9); val i = FloatArray(9)
        if (!SensorManager.getRotationMatrix(r, i, accel, mag)) return null
        val orientation = FloatArray(3)
        SensorManager.getOrientation(r, orientation)
        return orientation[0].toDouble()
    }

    private fun wrapAngle(a: Double): Double {
        var r = a
        while (r > Math.PI) r -= 2 * Math.PI
        while (r < -Math.PI) r += 2 * Math.PI
        return r
    }

    fun getRawGps(): Triple<Double, Double, Double> = Triple(rawGpsLat, rawGpsLon, rawGpsAlt)

    private fun log(msg: String, level: LogLevel = LogLevel.INFO) {
        listener.onLog(LogEntry(System.currentTimeMillis(), msg, level))
    }

    fun getPathPoints(): List<PathPoint> = pathPoints.toList()

    fun reset() {
        pathPoints.clear()
        stepDetector.reset()
        ekf.hardReset()
        gpsOriginLat = Double.NaN; gpsOriginLon = Double.NaN; gpsOriginAlt = Double.NaN
        lastGpsTimeMs = 0L; prevGpsNorth = Double.NaN; prevGpsEast = Double.NaN
        imuOnlyStartMs = 0L; initAltMSL = Double.NaN
        headingInitialised = false; fusedHeading = 0.0
        tickCount = 0; mode = NavigationMode.GPS_LOCK; demoActive = false
        log("navigation reset — origin cleared", LogLevel.WARN)
    }

    fun toGlobal(north: Double, east: Double): Pair<Double, Double> {
        if (gpsOriginLat.isNaN()) return Pair(0.0, 0.0)
        val r = 6_371_000.0
        val lat = gpsOriginLat + Math.toDegrees(north / r)
        val lon = gpsOriginLon + Math.toDegrees(east / (r * cos(Math.toRadians(gpsOriginLat))))
        return Pair(lat, lon)
    }

    private val Double.f1 get() = "%.1f".format(this)
}
