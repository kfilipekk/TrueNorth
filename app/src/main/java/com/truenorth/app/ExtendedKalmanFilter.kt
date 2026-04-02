package com.truenorth.app

import kotlin.math.*

//minimal matrix algebra
class Matrix private constructor(val rows: Int, val cols: Int) {
    val d: Array<DoubleArray> = Array(rows) { DoubleArray(cols) }

    companion object {
        operator fun invoke(rows: Int, cols: Int, init: ((Int, Int) -> Double)? = null): Matrix {
            val m = Matrix(rows, cols)
            if (init != null) for (i in 0 until rows) for (j in 0 until cols) m.d[i][j] = init(i, j)
            return m
        }
        fun identity(n: Int) = Matrix(n, n) { i, j -> if (i == j) 1.0 else 0.0 }
        fun fromVector(v: DoubleArray) = Matrix(v.size, 1) { i, _ -> v[i] }
        fun diagonal(v: DoubleArray) = Matrix(v.size, v.size) { i, j -> if (i == j) v[i] else 0.0 }
    }

    operator fun get(i: Int, j: Int): Double = d[i][j]
    operator fun set(i: Int, j: Int, v: Double) { d[i][j] = v }

    operator fun plus(o: Matrix): Matrix {
        require(rows == o.rows && cols == o.cols) { "size mismatch: ($rows,$cols) + (${o.rows},${o.cols})" }
        return Matrix(rows, cols) { i, j -> d[i][j] + o.d[i][j] }
    }

    operator fun minus(o: Matrix): Matrix {
        require(rows == o.rows && cols == o.cols)
        return Matrix(rows, cols) { i, j -> d[i][j] - o.d[i][j] }
    }

    operator fun times(o: Matrix): Matrix {
        require(cols == o.rows) { "size mismatch: ($rows,$cols) * (${o.rows},${o.cols})" }
        return Matrix(rows, o.cols) { i, j -> (0 until cols).sumOf { k -> d[i][k] * o.d[k][j] } }
    }

    operator fun times(scalar: Double): Matrix = Matrix(rows, cols) { i, j -> d[i][j] * scalar }

    fun T(): Matrix = Matrix(cols, rows) { i, j -> d[j][i] }

    //gauss-jordan inversion with pivoting
    fun inverse(): Matrix {
        require(rows == cols) { "can only invert square matrices" }
        val n = rows
        val aug = Array(n) { row -> DoubleArray(n * 2) { col ->
            if (col < n) d[row][col] else if (col - n == row) 1.0 else 0.0
        }}
        for (col in 0 until n) {
            var maxRow = col
            for (r in col + 1 until n) if (abs(aug[r][col]) > abs(aug[maxRow][col])) maxRow = r
            val tmp = aug[col]; aug[col] = aug[maxRow]; aug[maxRow] = tmp

            val pivot = aug[col][col]
            if (abs(pivot) < 1e-14) return identity(n) 

            for (j in 0 until n * 2) aug[col][j] /= pivot
            for (r in 0 until n) {
                if (r != col) {
                    val f = aug[r][col]
                    for (j in 0 until n * 2) aug[r][j] -= f * aug[col][j]
                }
            }
        }
        return Matrix(n, n) { i, j -> aug[i][j + n] }
    }

    fun toVector(): DoubleArray = DoubleArray(rows) { d[it][0] }
    fun copy(): Matrix = Matrix(rows, cols) { i, j -> d[i][j] }
}

//5-state extended kalman filter
//x = [ north_m, east_m, alt_m, heading_rad, speed_mps ]
class ExtendedKalmanFilter {

    private val I_N = 0; private val I_E = 1; private val I_A = 2
    private val I_H = 3; private val I_V = 4
    val DIM = 5

    //state vector
    private var x = Matrix.fromVector(DoubleArray(DIM))

    //error covariance
    private var P = Matrix.diagonal(doubleArrayOf(
        0.25, 0.25,   //north, east
        1.0,          //altitude
        0.04,         //heading
        0.04          //speed
    ))

    //process noise Q
    //tuned for pixel 7 pro high-grade imu
    private val Q = Matrix.diagonal(doubleArrayOf(
        0.001, 0.001, //position noise
        0.0002,       //altitude noise
        0.0005,       //heading noise
        0.005         //speed variability
    ))

    //measurement noise matrices
    private val R_baro  = Matrix.diagonal(doubleArrayOf(0.25))
    private val R_mag   = Matrix.diagonal(doubleArrayOf(0.04))
    private val R_step  = Matrix.diagonal(doubleArrayOf(0.04))
    private val R_cell  = Matrix.diagonal(doubleArrayOf(0.25))
    private val R_gps2d = Matrix.diagonal(doubleArrayOf(1.0, 1.0))

    //public accessors
    val north    get() = x[I_N, 0]
    val east     get() = x[I_E, 0]
    val altitude get() = x[I_A, 0]
    val headingRad get() = x[I_H, 0]
    val headingDeg get() = Math.toDegrees(x[I_H, 0]).let { d ->
        val wrapped = d % 360; if (wrapped < 0) wrapped + 360 else wrapped
    }
    val speed    get() = x[I_V, 0].coerceAtLeast(0.0)

    fun positionUncertaintyM() = sqrt(P[I_N, I_N] + P[I_E, I_E])
    fun headingUncertaintyDeg() = Math.toDegrees(sqrt(P[I_H, I_H]))

    fun getStateX(): DoubleArray = x.toVector()
    fun getCovP(): DoubleArray = DoubleArray(DIM) { P[it, it] }

    //predict step
    fun predict(dt: Double, gyroZRps: Double) {
        val h = x[I_H, 0]
        val v = x[I_V, 0]

        //non-linear propagation
        x[I_N, 0] += v * cos(h) * dt
        x[I_E, 0] += v * sin(h) * dt
        x[I_H, 0] = wrapAngle(h + gyroZRps * dt)
        x[I_V, 0] *= 0.995  //prevent velocity explosion

        //jacobian F
        val F = Matrix.identity(DIM)
        F[I_N, I_H] = -v * sin(h) * dt
        F[I_N, I_V] =  cos(h)     * dt
        F[I_E, I_H] =  v * cos(h) * dt
        F[I_E, I_V] =  sin(h)     * dt
        F[I_V, I_V] = 0.995

        //P = FPF' + Q*dt
        P = F * P * F.T() + Q * dt
    }

    //measurement updates
    fun updateBarometer(altitudeMeasuredM: Double) {
        val H = Matrix(1, DIM); H[0, I_A] = 1.0
        kalmanUpdate(H, Matrix.fromVector(doubleArrayOf(altitudeMeasuredM)), R_baro)
    }

    fun updateMagnetometer(headingMeasuredRad: Double) {
        val H = Matrix(1, DIM); H[0, I_H] = 1.0
        val innov = wrapAngle(headingMeasuredRad - x[I_H, 0])
        val z = Matrix.fromVector(doubleArrayOf(x[I_H, 0] + innov))
        kalmanUpdate(H, z, R_mag)
        x[I_H, 0] = wrapAngle(x[I_H, 0])
    }

    fun updateStepSpeed(stepSpeedMps: Double) {
        val H = Matrix(1, DIM); H[0, I_V] = 1.0
        kalmanUpdate(H, Matrix.fromVector(doubleArrayOf(stepSpeedMps)), R_step)
        x[I_V, 0] = x[I_V, 0].coerceAtLeast(0.0)
    }

    fun updateCellSpeedHint(speedMps: Double) {
        if (speedMps < 0.05) return
        val H = Matrix(1, DIM); H[0, I_V] = 1.0
        kalmanUpdate(H, Matrix.fromVector(doubleArrayOf(speedMps)), R_cell)
        x[I_V, 0] = x[I_V, 0].coerceAtLeast(0.0)
    }

    fun updateGps2D(northM: Double, eastM: Double, accuracyM: Float) {
        val H = Matrix(2, DIM)
        H[0, I_N] = 1.0; H[1, I_E] = 1.0
        val z = Matrix(2, 1); z[0, 0] = northM; z[1, 0] = eastM
        val scale = (accuracyM.toDouble() / 3.0).pow(2).coerceIn(0.1, 100.0)
        val R_scaled = R_gps2d * scale
        kalmanUpdate(H, z, R_scaled)
    }

    //generic ekf update
    private fun kalmanUpdate(H: Matrix, z: Matrix, R: Matrix) {
        val y = z - H * x               //innovation
        val S = H * P * H.T() + R       //innovation covariance
        val K = P * H.T() * S.inverse() //kalman gain
        x = x + K * y                   //state update
        
        //joseph form for numerical stability
        val I = Matrix.identity(DIM)
        val I_KH = I - K * H
        P = I_KH * P * I_KH.T() + K * R * K.T()
    }

    //resets
    fun resetPosition(north: Double, east: Double, alt: Double, heading: Double) {
        x[I_N, 0] = north
        x[I_E, 0] = east
        x[I_A, 0] = alt
        x[I_H, 0] = wrapAngle(heading)
        x[I_V, 0] = 0.0
        P = Matrix.diagonal(doubleArrayOf(0.25, 0.25, 1.0, 0.04, 0.04))
    }

    fun hardReset() {
        x = Matrix.fromVector(DoubleArray(DIM))
        P = Matrix.diagonal(doubleArrayOf(1.0, 1.0, 2.0, 0.2, 0.1))
    }

    private fun wrapAngle(a: Double): Double {
        var r = a
        while (r > PI) r -= 2 * PI
        while (r < -PI) r += 2 * PI
        return r
    }
}
