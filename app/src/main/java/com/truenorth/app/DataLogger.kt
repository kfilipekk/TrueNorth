package com.truenorth.app

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

//datalogger — exports csv telemetry for python analysis
class DataLogger(private val context: Context) {

    private var logFile: File? = null
    private val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.UK)

    //starts a fresh file with headers
    fun startNewSession(): String {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.UK).format(Date())
        val fileName = "TrueNorth_Log_$timestamp.csv"
        val dir = context.getExternalFilesDir(null)
        val file = File(dir, fileName)
        
        logFile = file
        
        val header = "timestamp,mode,gps_lat,gps_lon,gps_alt,ekf_lat,ekf_lon,ekf_alt,uncertainty,step_count,pressure_hpa\n"
        writeLine(header)
        
        return file.absolutePath
    }

    //record a snapshot of current engine state
    fun log(data: TelemetryData, engine: SensorFusionEngine) {
        val (lat, lon) = engine.toGlobal(data.northingM, data.eastingM)
        val (rLat, rLon, rAlt) = engine.getRawGps()
        
        val line = "${sdf.format(Date(data.timestamp))},${data.mode.displayName}," +
                   "$rLat,$rLon,$rAlt," +
                   "$lat,$lon,${data.altitudeMSL},${data.positionUncertaintyM}," +
                   "${data.stepCount},${data.pressureHPa}\n"
        
        writeLine(line)
    }

    //append a line to the file
    private fun writeLine(text: String) {
        logFile?.let {
            try {
                FileOutputStream(it, true).use { stream ->
                    stream.write(text.toByteArray())
                }
            } catch (e: Exception) {}
        }
    }
}
