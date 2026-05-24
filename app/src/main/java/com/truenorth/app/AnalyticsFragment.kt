package com.truenorth.app

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.truenorth.app.databinding.FragmentAnalyticsBinding

class AnalyticsFragment : Fragment() {

    private var _binding: FragmentAnalyticsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: TrueNorthViewModel by activityViewModels()

    private val MAX_POINTS = 200
    private var dataCount = 0f
    private var updateTick = 0

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentAnalyticsBinding.inflate(inflater, container, false)
        setupCharts()
        
        viewModel.telemetry.observe(viewLifecycleOwner) { data ->
            // decimate updates to 4Hz for readability (50ms * 5 = 250ms)
            // original sensor loop is 20Hz
            updateTick++
            if (updateTick % 5 == 0) {
                updateData(data)
            }
        }

        return binding.root
    }

    private fun setupCharts() {
        initChart(binding.chartDivergence, "EKF North (m)", "GPS North (m)", Color.CYAN, Color.RED)
        initChart(binding.chartPos, "Northing (m)", "Easting (m)", Color.CYAN, Color.MAGENTA)
        initChart(binding.chartSpeedVibe, "Speed (m/s)", "Vibration (x10)", Color.GREEN, Color.YELLOW)
        initChart(binding.chartResiduals, "Baro Res (m)", "Mag Res (deg)", Color.WHITE, Color.RED)
        initChart(binding.chartSteps, "Step Len (m)", "Cadence (Hz)", Color.CYAN, Color.YELLOW)
    }

    private fun initChart(chart: LineChart, label1: String, label2: String, color1: Int, color2: Int) {
        chart.apply {
            description.isEnabled = false
            setTouchEnabled(true)
            isDragEnabled = true
            setScaleEnabled(true)
            setPinchZoom(true)
            setBackgroundColor(Color.TRANSPARENT)
            
            legend.textColor = Color.WHITE
            xAxis.textColor = Color.GRAY
            axisLeft.textColor = Color.GRAY
            axisRight.isEnabled = false
            
            data = LineData(
                createSet(label1, color1),
                createSet(label2, color2)
            )
        }
    }

    private fun createSet(label: String, color: Int): LineDataSet {
        return LineDataSet(mutableListOf(), label).apply {
            this.color = color
            setDrawCircles(false)
            setDrawValues(false)
            lineWidth = 1.5f
            mode = LineDataSet.Mode.CUBIC_BEZIER
        }
    }

    fun updateData(telemetry: TelemetryData) {
        if (_binding == null) return

        addEntry(binding.chartDivergence, telemetry.northingM.toFloat(), telemetry.gpsNorthingM.toFloat())
        addEntry(binding.chartPos, telemetry.northingM.toFloat(), telemetry.eastingM.toFloat())
        addEntry(binding.chartSpeedVibe, telemetry.speedMps.toFloat(), (telemetry.vibrationLevel * 10).toFloat())
        addEntry(binding.chartResiduals, telemetry.baroResidualM.toFloat(), telemetry.magResidualDeg.toFloat())
        addEntry(binding.chartSteps, telemetry.stepLengthM.toFloat(), telemetry.cadenceHz.toFloat())
        
        dataCount += 1f
    }

    private fun addEntry(chart: LineChart, val1: Float, val2: Float) {
        val data = chart.data ?: return
        
        val set1 = data.getDataSetByIndex(0)
        val set2 = data.getDataSetByIndex(1)
        
        data.addEntry(Entry(dataCount, val1), 0)
        data.addEntry(Entry(dataCount, val2), 1)
        
        if (set1.entryCount > MAX_POINTS) {
            set1.removeEntry(0)
            set2.removeEntry(0)
            // Fix indices
            for (i in 0 until set1.entryCount) {
                set1.getEntryForIndex(i).x = i.toFloat()
                set2.getEntryForIndex(i).x = i.toFloat()
            }
        }
        
        data.notifyDataChanged()
        chart.notifyDataSetChanged()
        chart.setVisibleXRangeMaximum(MAX_POINTS.toFloat())
        chart.moveViewToX(dataCount)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
