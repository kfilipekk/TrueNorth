package com.truenorth.app

import android.content.Context
import android.os.Build
import android.telephony.*
import kotlin.math.*

//monitors cellular environment for pseudo-doppler hints
class CellSignalMonitor(private val context: Context) {

    private val telephony = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
    private val history = mutableMapOf<Long, ArrayDeque<CellObservation>>()
    private val MAX_HISTORY_PER_TOWER = 10
    private var lastEstimate = CellDopplerEstimate()

    //modern callback for android 12+ (pixel 7 pro)
    private var telephonyCallback: Any? = null

    init {
        setupCallback()
    }

    private fun setupCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && telephony != null) {
            val callback = object : TelephonyCallback(), TelephonyCallback.CellInfoListener, TelephonyCallback.SignalStrengthsListener {
                override fun onCellInfoChanged(cellInfo: List<CellInfo>) {
                    processCellInfo(cellInfo)
                }

                override fun onSignalStrengthsChanged(signalStrength: SignalStrength) {
                    val now = System.currentTimeMillis()
                    val rssi = signalStrength.cellSignalStrengths.firstOrNull()?.dbm ?: -127
                    
                    //force a 'primary cell' entry even if identity is hidden
                    //use a constant id for the 'serving cell' to allow rate calculation
                    val primaryId = 1L 
                    val obs = CellObservation(primaryId, rssi, "PRIMARY", now)
                    
                    val towerHistory = history.getOrPut(primaryId) { ArrayDeque() }
                    towerHistory.addLast(obs)
                    if (towerHistory.size > MAX_HISTORY_PER_TOWER) towerHistory.removeFirst()
                }
            }
            try {
                telephony.registerTelephonyCallback(context.mainExecutor, callback)
                telephonyCallback = callback
            } catch (e: SecurityException) {}
        }
    }

    private fun processCellInfo(cellInfo: List<CellInfo>) {
        val now = System.currentTimeMillis()
        
        //verbose logging for debugging hardware/android version mismatches
        if (cellInfo.isNotEmpty()) {
            val types = cellInfo.map { it.javaClass.simpleName }.distinct().joinToString()
            //we'll log this via a callback or internal state if needed, but for now just process
        }

        val observations = cellInfo.mapNotNull { extractObservation(it, now) }
        
        observations.forEach { obs ->
            val towerHistory = history.getOrPut(obs.cellId) { ArrayDeque() }
            towerHistory.addLast(obs)
            if (towerHistory.size > MAX_HISTORY_PER_TOWER) towerHistory.removeFirst()
        }
    }

    fun update(): CellDopplerEstimate {
        //trigger a refresh for legacy or if callback is slow
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                telephony?.requestCellInfoUpdate(context.mainExecutor, object : TelephonyManager.CellInfoCallback() {
                    override fun onCellInfo(cellInfo: List<CellInfo>) {
                        processCellInfo(cellInfo)
                    }
                })
            } catch (e: Exception) {}
        } else {
            try {
                telephony?.allCellInfo?.let { processCellInfo(it) }
            } catch (e: Exception) {}
        }
        
        val now = System.currentTimeMillis()
        history.entries.removeIf { it.value.last().timestampMs < now - 30_000 }

        var totalRate = 0.0
        var dopplerTowers = 0
        var bestRssi = -127

        history.forEach { (_, h) ->
            if (h.size >= 3) {
                val dt = (h.last().timestampMs - h.first().timestampMs) / 1000.0
                if (dt > 0.5) { //lowered dt required for faster update on high-end hardware
                    val drssi = h.last().rssiDbm - h.first().rssiDbm
                    val rate = abs(drssi / dt)
                    totalRate += rate
                    dopplerTowers++
                }
            }
            if (h.isNotEmpty()) {
                bestRssi = max(bestRssi, h.last().rssiDbm)
            }
        }

        val avgRate = if (dopplerTowers > 0) totalRate / dopplerTowers else 0.0
        val speedHint = (avgRate * 12.0).coerceIn(0.0, 30.0)
        val confidence = (dopplerTowers * 0.15f).coerceIn(0f, 0.4f)

        //visibleTowers should reflect all active signals in history, not just doppler-ready ones
        lastEstimate = CellDopplerEstimate(speedHint, confidence, history.size, bestRssi, avgRate)
        return lastEstimate
    }

    private fun extractObservation(ci: CellInfo, nowMs: Long): CellObservation? {
        return try {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && ci is CellInfoNr -> {
                    val rssi = (ci.cellSignalStrength as? CellSignalStrengthNr)?.csiRsrp ?: Int.MIN_VALUE
                    if (rssi == Int.MIN_VALUE) return null
                    
                    //on newer android versions, some identity fields might be unavailable (Long.MAX_VALUE)
                    //but we need a unique id. if nci is unavailable, we might try to hash pci/tac
                    val identity = ci.cellIdentity as? CellIdentityNr
                    val nci = identity?.nci ?: Long.MAX_VALUE
                    
                    val cellId = if (nci != Long.MAX_VALUE && nci != 0L) {
                        nci
                    } else {
                        //fallback to a pseudo-id based on physical cell id and tracking area code
                        val pci = identity?.pci ?: 0
                        val tac = identity?.tac ?: 0
                        if (pci != 0 && pci != Int.MAX_VALUE) {
                            (tac.toLong() shl 16) or pci.toLong()
                        } else return null
                    }

                    CellObservation(
                        cellId      = cellId,
                        rssiDbm     = rssi,
                        technology  = "5G NR",
                        timestampMs = nowMs
                    )
                }
                ci is CellInfoLte -> {
                    val rssi = ci.cellSignalStrength.rsrp
                    if (rssi == Int.MIN_VALUE) return null
                    
                    val identity = ci.cellIdentity
                    val ciVal = identity.ci.toLong()
                    
                    val cellId = if (ciVal != Int.MAX_VALUE.toLong() && ciVal != 0L) {
                        ciVal
                    } else {
                        val pci = identity.pci
                        val tac = identity.tac
                        if (pci != 0 && pci != Int.MAX_VALUE) {
                            (tac.toLong() shl 16) or pci.toLong()
                        } else return null
                    }

                    CellObservation(
                        cellId      = cellId,
                        rssiDbm     = rssi,
                        technology  = "LTE",
                        timestampMs = nowMs
                    )
                }
                else -> null
            }
        } catch (e: Exception) { null }
    }

    fun clear() = history.clear()
}
