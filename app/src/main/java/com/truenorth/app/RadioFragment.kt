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
import com.truenorth.app.databinding.FragmentRadioBinding

class RadioFragment : Fragment() {

    private var _binding: FragmentRadioBinding? = null
    private val binding get() = _binding!!
    private val viewModel: TrueNorthViewModel by activityViewModels()

    private val MAX_POINTS = 200
    private var dataCount = 0f
    private var updateTick = 0

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentRadioBinding.inflate(inflater, container, false)
        setupCharts()
        
        viewModel.telemetry.observe(viewLifecycleOwner) { data ->
            updateTick++
            // decimate slightly less than analytics, update at 2Hz (every 500ms)
            // since rf environment changes slowly
            if (updateTick % 10 == 0) {
                updateData(data)
            }
        }

        return binding.root
    }

    private fun setupCharts() {
        initChart(binding.chartRfSignal, "Cell RSSI", "Wi-Fi RSSI", Color.CYAN, Color.GREEN)
        initChart(binding.chartRfStability, "Wi-Fi Scans", "Cell Towers", Color.YELLOW, Color.WHITE)
    }

    private fun initChart(chart: LineChart, label1: String, label2: String, color1: Int, color2: Int) {
        chart.apply {
            description.isEnabled = false
            legend.textColor = Color.WHITE
            xAxis.textColor = Color.GRAY
            axisLeft.textColor = Color.GRAY
            axisRight.isEnabled = false
            data = LineData(createSet(label1, color1), createSet(label2, color2))
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

        binding.txtCellStatus.text = "RSSI: ${telemetry.bestCellRssi} dBm\nTowers: ${telemetry.visibleCells}"
        binding.txtWifiStatus.text = "RSSI: ${telemetry.bestWifiRssi} dBm\nScans: ${telemetry.wifiScans}"

        addEntry(binding.chartRfSignal, telemetry.bestCellRssi.toFloat(), telemetry.bestWifiRssi.toFloat())
        addEntry(binding.chartRfStability, telemetry.wifiScans.toFloat(), telemetry.visibleCells.toFloat())
        
        dataCount += 1f
    }

    private fun addEntry(chart: LineChart, val1: Float, val2: Float) {
        val data = chart.data ?: return
        data.addEntry(Entry(dataCount, val1), 0)
        data.addEntry(Entry(dataCount, val2), 1)
        
        if (data.getDataSetByIndex(0).entryCount > MAX_POINTS) {
            data.getDataSetByIndex(0).removeEntry(0)
            data.getDataSetByIndex(1).removeEntry(0)
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
