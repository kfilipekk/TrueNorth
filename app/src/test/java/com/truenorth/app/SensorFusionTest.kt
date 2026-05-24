package com.truenorth.app

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI

class SensorFusionTest {

    @Test
    fun testEKFPrediction() {
        val ekf = ExtendedKalmanFilter()
        ekf.resetPosition(0.0, 0.0, 0.0, 0.0)
        
        // Inject speed of 1.0 m/s via step update multiple times to overcome initial low P
        repeat(10) { ekf.updateStepSpeed(1.0) }
        
        // Predict 1 second ahead
        ekf.predict(1.0, 0.0)
        
        // Should be at north > 0.5
        assertTrue("North position should increase", ekf.north > 0.5)
        assertEquals(0.0, ekf.east, 0.1)
    }

    @Test
    fun testBarometerUpdate() {
        val ekf = ExtendedKalmanFilter()
        ekf.resetPosition(0.0, 0.0, 0.0, 0.0)
        
        // Update altitude multiple times to overcome initial covariance
        repeat(10) { ekf.updateBarometer(100.0) }
        
        assertTrue("Altitude should approach 100", ekf.altitude > 90.0)
    }

    @Test
    fun testMagnetometerHeading() {
        val ekf = ExtendedKalmanFilter()
        ekf.resetPosition(0.0, 0.0, 0.0, 0.0)
        
        // Update heading multiple times
        repeat(10) { ekf.updateMagnetometer(PI / 2.0) }
        
        assertTrue("Heading should approach 90 deg", ekf.headingDeg > 80.0 && ekf.headingDeg < 100.0)
    }

    @Test
    fun testAccelIntegration() {
        // simulate a bike start (0 to 5m/s in 5 seconds)
        val ekf = ExtendedKalmanFilter()
        ekf.resetPosition(0.0, 0.0, 0.0, 0.0)
        
        val dt = 0.05
        val constantAccel = 2.0 // 2 m/s^2 (strong bike start)
        
        for (i in 0 until 100) { 
            ekf.predict(dt, 0.0)
            
            // updated engine logic
            val deltaV = constantAccel * dt * 0.8
            val predictedSpeed = (ekf.speed + deltaV).coerceAtLeast(0.0)
            
            // manually simulate the engine calling update with the hint
            val H = Matrix(1, 5); H[0, 4] = 1.0
            val speedR = Matrix.diagonal(doubleArrayOf(0.01))
            ekf.kalmanUpdate(H, Matrix.fromVector(doubleArrayOf(predictedSpeed)), speedR)
        }
        
        assertTrue("Speed should have increased to > 1.0 (current: ${ekf.speed})", ekf.speed > 1.0)
        assertTrue("Position should have changed (current: ${ekf.north})", ekf.north > 2.0)
    }
}
