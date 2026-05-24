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
        
        val header = "timestamp,mode,gps_lat,gps_lon,gps_alt,ekf_lat,ekf_lon,ekf_alt,uncertainty,step_count,pressure_hpa," +
                     "accel_x,accel_y,accel_z,gyro_x,gyro_y,gyro_z,mag_x,mag_y,mag_z,cell_towers,best_rssi,vibration," +
                     "ekf_n,ekf_e,ekf_a,ekf_h,ekf_v,cadence,step_len,baro_res,mag_res,wifi_scans,best_wifi_rssi,gps_n_m,gps_e_m\n"
        writeLine(header)
        
        return file.absolutePath
    }

    //record a snapshot of current engine state
    fun log(data: TelemetryData, engine: SensorFusionEngine) {
        val (lat, lon) = engine.toGlobal(data.northingM, data.eastingM)
        val (rLat, rLon, rAlt) = engine.getRawGps()
        val s = data.rawSensors
        
        val line = "${sdf.format(Date(data.timestamp))},${data.mode.displayName}," +
                   "$rLat,$rLon,$rAlt," +
                   "$lat,$lon,${data.altitudeMSL},${data.positionUncertaintyM}," +
                   "${data.stepCount},${data.pressureHPa}," +
                   "${s.accelMps2[0]},${s.accelMps2[1]},${s.accelMps2[2]}," +
                   "${s.gyroRps[0]},${s.gyroRps[1]},${s.gyroRps[2]}," +
                   "${s.magUt[0]},${s.magUt[1]},${s.magUt[2]}," +
                   "${data.visibleCells},${data.bestCellRssi},${data.vibrationLevel}," +
                   "${data.ekfStateX[0]},${data.ekfStateX[1]},${data.ekfStateX[2]},${data.ekfStateX[3]},${data.ekfStateX[4]}," +
                   "${data.cadenceHz},${data.stepLengthM},${data.baroResidualM},${data.magResidualDeg}," +
                   "${data.wifiScans},${data.bestWifiRssi},${data.gpsNorthingM},${data.gpsEastingM}\n"
        
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
