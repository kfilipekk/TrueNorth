package com.truenorth.app

import android.content.Context
import android.graphics.Color
import android.graphics.DashPathEffect
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.truenorth.app.databinding.FragmentMapBinding
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

class MapFragment : Fragment() {

    private var _binding: FragmentMapBinding? = null
    val binding get() = _binding!!
    
    private var posMarker: Marker? = null
    private val predictedPathLine = Polyline()
    private val actualPathLine = Polyline()
    
    private var listener: MapInteractionListener? = null
    private val viewModel: TrueNorthViewModel by activityViewModels()

    interface MapInteractionListener {
        fun onJammingClicked()
        fun onForceStartClicked()
        fun onSpoofingClicked()
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        if (context is MapInteractionListener) {
            listener = context
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentMapBinding.inflate(inflater, container, false)
        setupMap()
        setupButtons()
        
        viewModel.telemetry.observe(viewLifecycleOwner) { data ->
            updateTelemetry(data, data.lat, data.lon)
        }
        
        viewModel.pathPoint.observe(viewLifecycleOwner) { pt ->
            addPathPoint(pt, pt.lat, pt.lon)
        }

        return binding.root
    }

    private fun setupMap() {
        binding.map.setMultiTouchControls(true)
        binding.map.controller.setZoom(18.0)
        
        predictedPathLine.outlinePaint.color = Color.parseColor("#00D4FF")
        predictedPathLine.outlinePaint.strokeWidth = 6f
        binding.map.overlays.add(predictedPathLine)
        
        actualPathLine.outlinePaint.color = Color.parseColor("#FF00FF")
        actualPathLine.outlinePaint.strokeWidth = 4f
        actualPathLine.outlinePaint.pathEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f)
        binding.map.overlays.add(actualPathLine)
    }

    private fun setupButtons() {
        binding.btnJamming.setOnClickListener { listener?.onJammingClicked() }
        binding.btnForceStart.setOnClickListener { listener?.onForceStartClicked() }
        binding.btnSpoofing.setOnClickListener { listener?.onSpoofingClicked() }
    }

    fun updateTelemetry(data: TelemetryData, lat: Double, lon: Double) {
        if (_binding == null) return
        
        binding.navigationMap.updateTelemetry(data)
        
        binding.btnForceStart.visibility = if (data.northingM == 0.0 && data.eastingM == 0.0) View.VISIBLE else View.GONE
        
        binding.txtCoords.text = "LAT: %.6f LON: %.6f".format(lat, lon)
        binding.txtAlt.text = "ALT: %.1fm (Δ %.1fm)".format(data.altitudeMSL, data.altitudeDeltaM)
        binding.txtMode.text = "MODE: ${data.mode.displayName}"
        binding.txtMode.setTextColor(data.mode.color)
        binding.txtUncertainty.text = "UNCERTAINTY: %.1fm".format(data.positionUncertaintyM)
        
        updateSensorStatus(data)
        
        val gp = GeoPoint(lat, lon)
        if (posMarker == null) {
            posMarker = Marker(binding.map)
            posMarker?.title = "TrueNorth"
            posMarker?.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            binding.map.overlays.add(posMarker)
        }
        posMarker?.position = gp
        binding.map.controller.animateTo(gp)
    }

    private fun updateSensorStatus(data: TelemetryData) {
        val okColor = Color.parseColor("#00FF88")
        val warnColor = Color.parseColor("#FFB300")
        val errorColor = Color.RED

        binding.statusImu.setTextColor(if (data.confidence.imu > 0.1) okColor else okColor) // Keeping existing logic
        
        if (data.confidence.barometer > 0.5) {
            binding.statusBaro.text = "BARO [LOCK]"
            binding.statusBaro.setTextColor(okColor)
        } else {
            binding.statusBaro.text = "BARO [OFF]"
            binding.statusBaro.setTextColor(errorColor)
        }

        if (data.visibleCells > 0) {
            binding.statusCell.text = "CELL [${data.visibleCells} TWR]"
            binding.statusCell.setTextColor(if (data.confidence.cellDoppler > 0.1) okColor else warnColor)
        } else {
            binding.statusCell.text = "CELL [NO SIG]"
            binding.statusCell.setTextColor(errorColor)
        }

        binding.statusVibe.text = "VIBE [%.1f]".format(data.vibrationLevel)
        when {
            data.vibrationLevel < 0.1 -> binding.statusVibe.setTextColor(okColor)
            data.vibrationLevel < 0.5 -> binding.statusVibe.setTextColor(warnColor)
            else -> binding.statusVibe.setTextColor(errorColor)
        }
    }

    fun addPathPoint(point: PathPoint, lat: Double, lon: Double) {
        if (_binding == null) return
        binding.navigationMap.addPathPoint(point)
        val gp = GeoPoint(lat, lon)
        if (point.isGpsActual) {
            actualPathLine.addPoint(gp)
        } else {
            predictedPathLine.addPoint(gp)
        }
        binding.map.invalidate()
    }

    override fun onResume() {
        super.onResume()
        _binding?.map?.onResume()
    }

    override fun onPause() {
        super.onPause()
        _binding?.map?.onPause()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
