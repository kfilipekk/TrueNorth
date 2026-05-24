package com.truenorth.app

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

class TrueNorthViewModel : ViewModel() {
    private val _telemetry = MutableLiveData<TelemetryData>()
    val telemetry: LiveData<TelemetryData> = _telemetry

    private val _pathPoint = MutableLiveData<PathPoint>()
    val pathPoint: LiveData<PathPoint> = _pathPoint

    private val _logEntry = MutableLiveData<LogEntry>()
    val logEntry: LiveData<LogEntry> = _logEntry

    fun updateTelemetry(data: TelemetryData) {
        _telemetry.postValue(data)
    }

    fun addPathPoint(point: PathPoint) {
        _pathPoint.postValue(point)
    }

    fun addLog(entry: LogEntry) {
        _logEntry.postValue(entry)
    }
}
