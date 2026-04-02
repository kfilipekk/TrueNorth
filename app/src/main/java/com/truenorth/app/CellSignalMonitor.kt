package com.truenorth.app

import android.content.Context
import android.os.Build
import android.telephony.*
import kotlin.math.*

//cell signal monitor — pseudo-doppler positioning aid
class CellSignalMonitor(private val context: Context) {

    private val telephony: TelephonyManager? = 
        context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager

    private val history = mutableMapOf<Long, ArrayDeque<CellObservation>>()
    private val MAX_HISTORY_PER_TOWER = 10

    private var lastEstimate = CellDopplerEstimate()

    //update tower info and estimate speed
    fun update(): CellDopplerEstimate {
        //request cell refresh for android 10+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                telephony?.requestCellInfoUpdate(context.mainExecutor, object : TelephonyManager.CellInfoCallback() {
                    override fun onCellInfo(cellInfo: List<CellInfo>) {}
                })
            } catch (e: Exception) {}
        }
        
        val observations = readAllCells()
        val now = System.currentTimeMillis()

        observations.forEach { obs ->
            val towerHistory = history.getOrPut(obs.cellId) { ArrayDeque() }
            towerHistory.addLast(obs)
            if (towerHistory.size > MAX_HISTORY_PER_TOWER) towerHistory.removeFirst()
        }

        //cleanup old towers (stale > 30s)
        history.entries.removeIf { it.value.last().timestampMs < now - 30_000 }

        //calculate scalar speed hints from rssi rates
        var totalRate = 0.0
        var towerCount = 0
        var bestRssi = -120

        history.forEach { (_, h) ->
            if (h.size >= 3) {
                val dt = (h.last().timestampMs - h.first().timestampMs) / 1000.0
                if (dt > 1.0) {
                    val drssi = h.last().rssiDbm - h.first().rssiDbm
                    val rate = abs(drssi / dt)
                    totalRate += rate
                    towerCount++
                }
            }
            bestRssi = max(bestRssi, h.last().rssiDbm)
        }

        val avgRate = if (towerCount > 0) totalRate / towerCount else 0.0
        
        //0.1 db/s roughly maps to walking speed (0.8 - 1.5 m/s)
        val speedHint = (avgRate * 12.0).coerceIn(0.0, 30.0)
        val confidence = (towerCount * 0.15f).coerceIn(0f, 0.4f)

        lastEstimate = CellDopplerEstimate(speedHint, confidence, towerCount, bestRssi, avgRate)
        return lastEstimate
    }

    //fetch current tower observations
    private fun readAllCells(): List<CellObservation> {
        return try {
            val cells = telephony?.allCellInfo ?: return emptyList()
            val now = System.currentTimeMillis()
            cells.mapNotNull { ci -> extractObservation(ci, now) }
        } catch (e: Exception) { emptyList() }
    }

    private fun extractObservation(ci: CellInfo, nowMs: Long): CellObservation? {
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && ci is CellInfoNr -> {
                val rssi = (ci.cellSignalStrength as CellSignalStrengthNr).csiRsrp
                if (rssi == Int.MIN_VALUE) null
                else CellObservation(
                    cellId      = (ci.cellIdentity as? CellIdentityNr)?.nci ?: return null,
                    rssiDbm     = rssi,
                    technology  = "5G NR",
                    timestampMs = nowMs
                )
            }
            ci is CellInfoLte -> {
                val rssi = ci.cellSignalStrength.rsrp
                if (rssi == Int.MIN_VALUE) null
                else CellObservation(
                    cellId      = ci.cellIdentity.ci.toLong().takeIf { it >= 0 } ?: return null,
                    rssiDbm     = rssi,
                    technology  = "LTE",
                    timestampMs = nowMs
                )
            }
            ci is CellInfoGsm -> {
                val rssi = ci.cellSignalStrength.dbm
                if (rssi == Int.MIN_VALUE) null
                else CellObservation(
                    cellId      = ci.cellIdentity.cid.toLong().takeIf { it >= 0 } ?: return null,
                    rssiDbm     = rssi,
                    technology  = "GSM",
                    timestampMs = nowMs
                )
            }
            ci is CellInfoWcdma -> {
                val rssi = ci.cellSignalStrength.dbm
                if (rssi == Int.MIN_VALUE) null
                else CellObservation(
                    cellId      = ci.cellIdentity.cid.toLong().takeIf { it >= 0 } ?: return null,
                    rssiDbm     = rssi,
                    technology  = "WCDMA",
                    timestampMs = nowMs
                )
            }
            else -> null
        }
    }

    //utilities
    fun visibleTowerCount() = history.size
    fun bestRssi(): Int = history.values.mapNotNull { it.lastOrNull()?.rssiDbm }.maxOrNull() ?: -120
    fun clear() = history.clear()
}
