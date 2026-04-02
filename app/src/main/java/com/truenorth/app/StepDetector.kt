package com.truenorth.app

import kotlin.math.*

//step detector using the weinberg algorithm
class StepDetector(private val onStep: (stepLengthM: Double, speedMps: Double) -> Unit) {

    //low-pass gravity estimation
    private var gx = 0f
    private var gy = 0f
    private var gz = 0f
    private val GRAVITY_ALPHA = 0.8f

    //peak detection thresholds
    private val UPPER_THRESH = 10.5
    private val LOWER_THRESH = 9.2
    private val MIN_INTERVAL = 250L
    private val MAX_INTERVAL = 2000L

    private var aboveUpper = false
    private var cyclePeak = 0.0
    private var cycleTrough = 20.0
    private var inCycle = false

    private var lastStepMs = 0L
    private var stepCount = 0
    private val recentIntervals = ArrayDeque<Long>()

    //weinberg constant (average human)
    private val K_WEINBERG = 0.45

    //process a raw imu sample
    fun processSample(ax: Float, ay: Float, az: Float, timestampMs: Long) {
        //filter gravity
        gx = GRAVITY_ALPHA * gx + (1 - GRAVITY_ALPHA) * ax
        gy = GRAVITY_ALPHA * gy + (1 - GRAVITY_ALPHA) * ay
        gz = GRAVITY_ALPHA * gz + (1 - GRAVITY_ALPHA) * az

        //linear acceleration magnitude
        val mag = sqrt(ax.toDouble().pow(2) + ay.toDouble().pow(2) + az.toDouble().pow(2))

        if (mag > UPPER_THRESH && !aboveUpper) {
            aboveUpper = true
            inCycle = true
        }

        if (mag < LOWER_THRESH && aboveUpper) {
            aboveUpper = false
        }

        //track peaks and troughs within a cycle
        if (inCycle) {
            if (mag > cyclePeak) cyclePeak = mag
            if (mag < cycleTrough) cycleTrough = mag
        }

        //step event triggered on crossing the baseline after a peak
        if (inCycle && mag < 9.8 && aboveUpper == false) {
            val interval = timestampMs - lastStepMs
            
            if (interval in MIN_INTERVAL..MAX_INTERVAL) {
                stepCount++
                
                //weinberg formula for step length
                val stepLen = K_WEINBERG * (cyclePeak - cycleTrough).pow(0.25)
                
                recentIntervals.addLast(interval)
                if (recentIntervals.size > 5) recentIntervals.removeFirst()
                
                val avgInterval = recentIntervals.average()
                val speed = stepLen / (avgInterval / 1000.0)
                
                onStep(stepLen, speed)
                lastStepMs = timestampMs
            }
            
            //reset cycle
            inCycle = false
            cyclePeak = 0.0
            cycleTrough = 20.0
        }
    }

    private fun avgIntervalMs(): Long {
        if (recentIntervals.isEmpty()) return 0L
        return recentIntervals.average().toLong()
    }

    fun cadenceHz(): Double {
        val avg = avgIntervalMs()
        if (avg == 0L) return 0.0
        return 1000.0 / avg
    }

    fun stepCount() = stepCount

    fun estimatedStepLengthM(): Double {
        //fallback for UI
        return 0.75 
    }

    fun reset() {
        stepCount = 0
        lastStepMs = 0L
        recentIntervals.clear()
        gx = 0f; gy = 0f; gz = 0f
    }
}
