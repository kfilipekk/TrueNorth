package com.truenorth.app

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class DriftTest {

    @Test
    fun testLongDurationWalkingDrift() {
        val ekf = ExtendedKalmanFilter()
        ekf.resetPosition(0.0, 0.0, 0.0, 0.0)
        
        // simulate a 20-minute walk in a square (5 min per side)
        // speed = 1.4 m/s (brisk walk)
        // total distance = 1.4 * 60 * 20 = 1680m
        // each side = 420m
        
        val dt = 0.1 // 10 Hz
        val totalTicks = (20 * 60 / dt).toInt()
        val sideTicks = totalTicks / 4
        
        val walkSpeed = 1.4
        
        for (i in 0 until totalTicks) {
            val heading = (i / sideTicks) * (PI / 2.0)
            
            // simulate gyro noise
            val gyroZ = 0.0 + (Math.random() - 0.5) * 0.001
            
            ekf.predict(dt, gyroZ)
            
            // simulate step detections (1.4 m/s with some noise)
            val stepSpeed = walkSpeed + (Math.random() - 0.5) * 0.2
            ekf.updateStepSpeed(stepSpeed)
            
            // simulate magnetometer (heading with noise)
            val magHeading = heading + (Math.random() - 0.5) * 0.05
            ekf.updateMagnetometer(magHeading)
        }
        
        // After 20 mins, should be close to (0,0)
        val finalDist = sqrt(ekf.north.pow(2) + ekf.east.pow(2))
        println("Final walking drift after 20 mins: ${finalDist}m")
        
        // We expect less than 50m drift (approx 3% of distance)
        assertTrue("Walking drift too high: ${finalDist}m", finalDist < 50.0)
    }

    @Test
    fun testLongDurationCyclingDrift() {
        val ekf = ExtendedKalmanFilter()
        ekf.resetPosition(0.0, 0.0, 0.0, 0.0)
        
        // simulate a 20-minute cycle in a square
        // speed = 5.0 m/s (moderate cycling)
        // each side = 1500m
        
        val dt = 0.1
        val totalTicks = (20 * 60 / dt).toInt()
        val sideTicks = totalTicks / 4
        
        // gravity tracking for better linear accel estimation
        var gravityVec = doubleArrayOf(0.0, 0.0, 9.81)
        val alpha = 0.95
        
        for (i in 0 until totalTicks) {
            val heading = (i / sideTicks) * (PI / 2.0)
            ekf.predict(dt, 0.0)
            
            // cycling has no steps, uses improved IMU integration fallback
            val targetAccel = if (i < 100) 1.5 else 0.0 // faster start
            
            // simulate raw accelerometer reading with gravity and noise
            val rawAccelX = targetAccel * cos(heading) + (Math.random() - 0.5) * 0.1
            val rawAccelY = targetAccel * sin(heading) + (Math.random() - 0.5) * 0.1
            val rawAccelZ = 9.81 + (Math.random() - 0.5) * 0.1
            
            // simulate low-pass filter for gravity
            gravityVec[0] = gravityVec[0] * alpha + rawAccelX * (1 - alpha)
            gravityVec[1] = gravityVec[1] * alpha + rawAccelY * (1 - alpha)
            gravityVec[2] = gravityVec[2] * alpha + rawAccelZ * (1 - alpha)
            
            // estimate linear acceleration
            val linX = rawAccelX - gravityVec[0]
            val linY = rawAccelY - gravityVec[1]
            val linZ = rawAccelZ - gravityVec[2]
            val linearAccel = (sqrt(linX.pow(2) + linY.pow(2) + linZ.pow(2)) - 0.02).coerceAtLeast(0.0)
            
            if (linearAccel > 0.05) {
                val deltaV = linearAccel * dt * 0.8
                val predictedSpeed = (ekf.speed + deltaV).coerceAtLeast(0.0)
                ekf.updateCellSpeedHint(predictedSpeed, 0.05) // use realistic R
            }
            
            ekf.updateMagnetometer(heading + (Math.random() - 0.5) * 0.05)
        }
        
        val finalDist = sqrt(ekf.north.pow(2) + ekf.east.pow(2))
        println("Final cycling drift after 20 mins: ${finalDist}m")
        
        // For cycling, we expect more drift than walking, but target < 60m
        assertTrue("Cycling drift too high: ${finalDist}m", finalDist < 60.0)
    }
}
