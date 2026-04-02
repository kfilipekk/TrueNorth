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
}
