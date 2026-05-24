package com.truenorth.app

import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.truenorth.app.databinding.FragmentLogBinding
import java.text.SimpleDateFormat
import java.util.*

class LiveLogFragment : Fragment() {

    private var _binding: FragmentLogBinding? = null
    private val binding get() = _binding!!
    private val viewModel: TrueNorthViewModel by activityViewModels()
    
    private val logBuffer = StringBuilder()
    private val MAX_LOG_SIZE = 5000
    private val sdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.UK)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentLogBinding.inflate(inflater, container, false)
        binding.txtFullLog.movementMethod = ScrollingMovementMethod()
        binding.txtFullLog.text = logBuffer.toString()
        
        viewModel.logEntry.observe(viewLifecycleOwner) { entry ->
            addLog(entry)
        }

        return binding.root
    }

    fun addLog(entry: LogEntry) {
        val time = sdf.format(Date(entry.timestampMs))
        val line = "[$time] [${entry.level}] ${entry.message}\n"
        
        logBuffer.insert(0, line)
        if (logBuffer.length > MAX_LOG_SIZE) {
            logBuffer.setLength(MAX_LOG_SIZE)
        }
        
        activity?.runOnUiThread {
            _binding?.txtFullLog?.text = logBuffer.toString()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
