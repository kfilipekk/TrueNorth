package com.truenorth.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.util.Log

// monitors wifi environment for local positioning hints
class WifiSignalMonitor(private val context: Context) {

    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private var lastScanResults = listOf<ScanResult>()
    
    // simulate a "database" of known wifi routers for the demo
    // in a real app, this would query google/apple location services
    private val mockWifiDb = mapOf(
        "00:11:22:33:44:55" to Pair(52.1983, 0.1205), // near origin
        "AA:BB:CC:DD:EE:FF" to Pair(52.1995, 0.1210)  // 150m north
    )

    private val wifiScanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val success = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)
            if (success) {
                try {
                    lastScanResults = wifiManager.scanResults
                } catch (e: SecurityException) {
                    Log.e("TrueNorth", "wifi scan permission missing")
                }
            }
        }
    }

    fun start() {
        val intentFilter = IntentFilter()
        intentFilter.addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        context.registerReceiver(wifiScanReceiver, intentFilter)
    }

    fun stop() {
        try {
            context.unregisterReceiver(wifiScanReceiver)
        } catch (e: Exception) {}
    }

    // returns a weighted position estimate based on known BSSIDs
    fun getWifiPositionEstimate(): Pair<DoubleArray, Float>? {
        val knownScans = lastScanResults.filter { mockWifiDb.containsKey(it.BSSID) }
        if (knownScans.isEmpty()) return null

        var totalWeight = 0.0
        var latSum = 0.0
        var lonSum = 0.0

        for (scan in knownScans) {
            // weight based on signal strength (RSSI)
            // rssi typically -30 (close) to -90 (far)
            val weight = Math.pow(10.0, (scan.level + 30.0) / 20.0).coerceIn(0.01, 1.0)
            val coords = mockWifiDb[scan.BSSID]!!
            
            latSum += coords.first * weight
            lonSum += coords.second * weight
            totalWeight += weight
        }

        if (totalWeight < 0.1) return null
        
        // accuracy estimate (simplified)
        val accuracy = (30.0 / totalWeight).toFloat().coerceIn(5f, 50f)
        
        return Pair(doubleArrayOf(latSum / totalWeight, lonSum / totalWeight), accuracy)
    }

    fun getScanCount() = lastScanResults.size
    fun getBestRssi() = lastScanResults.maxByOrNull { it.level }?.level ?: -127
}
