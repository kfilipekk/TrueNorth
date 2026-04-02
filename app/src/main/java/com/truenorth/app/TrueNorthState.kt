package com.truenorth.app

import android.graphics.Color

//vector types
data class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Double) = Vec3(x * s, y * s, z * s)
    fun magnitude() = Math.sqrt(x * x + y * y + z * z)
    fun normalize(): Vec3 {
        val m = magnitude()
        return if (m > 1e-9) Vec3(x / m, y / m, z / m) else Vec3(0.0, 0.0, 1.0)
    }
}

//navigation modes
enum class NavigationMode(
    val displayName: String,
    val shortName: String,
    val color: Int,
    val bgColor: Int
) {
    GPS_LOCK(
        "GPS LOCK",      "GPS",
        Color.parseColor("#00FF88"),
        Color.parseColor("#0D3322")
    ),
    GPS_DEGRADED(
        "GPS DEGRADED",  "DEGRAD",
        Color.parseColor("#FFB300"),
        Color.parseColor("#332900")
    ),
    TRUENORTH(
        "TRUENORTH ACTIVE", "TRUENORTH",
        Color.parseColor("#00D4FF"),
        Color.parseColor("#002433")
    ),
    DEMO(
        "DEMO MODE",     "DEMO",
        Color.parseColor("#FF6B35"),
        Color.parseColor("#331500")
    )
}

//confidence bundle
data class SensorConfidence(
    val gps: Float = 1f,       //gps signal quality 0-1
    val imu: Float = 0f,       //dead reckoning confidence 0-1
    val barometer: Float = 0f, //barometric altitude confidence 0-1
    val cellDoppler: Float = 0f, //cell signal doppler confidence 0-1
    val overall: Float = 1f    //fused truenorth confidence 0-1
)

//primary telemetry output
data class TelemetryData(
    val northingM: Double = 0.0,      //metres north from origin
    val eastingM: Double = 0.0,       //metres east from origin
    val altitudeMSL: Double = 0.0,    //metres above mean sea level
    val altitudeDeltaM: Double = 0.0, //metres gained/lost since start
    val headingDeg: Double = 0.0,     //0–360 true
    val speedMps: Double = 0.0,       //m/s
    val stepCount: Int = 0,
    val pressureHPa: Double = 1013.25,
    val positionUncertaintyM: Double = 0.0, //ekf 1-sigma position error
    val headingUncertaintyDeg: Double = 0.0,
    val imuDriftMs: Long = 0L,        //time running in pure imu mode
    val mode: NavigationMode = NavigationMode.GPS_LOCK,
    val confidence: SensorConfidence = SensorConfidence(),
    val visibleCells: Int = 0,
    val bestCellRssi: Int = -120,
    val gpsAccuracyM: Float = 0f,
    val gpsSatellites: Int = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val ekfStateX: DoubleArray = DoubleArray(5),
    val ekfCovP: DoubleArray = DoubleArray(5) //diagonal elements of p
)

//path recording
data class PathPoint(
    val northM: Double,
    val eastM: Double,
    val altM: Double,
    val timestamp: Long,
    val mode: NavigationMode,
    val uncertaintyM: Double = 0.0,
    val isGpsActual: Boolean = false //distinguish actual vs predicted
)

//cell observation
data class CellObservation(
    val cellId: Long,
    val rssiDbm: Int,
    val technology: String,
    val timestampMs: Long
)

data class CellDopplerEstimate(
    val speedHintMps: Double = 0.0,   //estimated scalar speed from rssi rate
    val confidence: Float = 0f,
    val visibleTowers: Int = 0,
    val bestRssi: Int = -120,
    val rssiRateDbs: Double = 0.0     //db/s for display
)

//sensor raw bundle
data class RawSensorSnapshot(
    val accelMps2: FloatArray = FloatArray(3),
    val gyroRps: FloatArray = FloatArray(3),
    val magUt: FloatArray = FloatArray(3),
    val pressurePa: Float = 101325f,
    val hasMag: Boolean = false,
    val hasGyro: Boolean = false,
    val hasBaro: Boolean = false
)

//log entry
data class LogEntry(
    val timestampMs: Long,
    val message: String,
    val level: LogLevel = LogLevel.INFO
)

enum class LogLevel { INFO, WARN, ERROR, SUCCESS }
