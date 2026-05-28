package com.example.wearairmouse

import android.content.SharedPreferences
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import android.util.Log
import com.example.wearairmouse.databinding.ActivitySettingsBinding

/**
 * Экран настроек AirMouse.
 * Позволяет настроить чувствительность, мёртвую зону и включение сенсоров.
 */
class SettingsActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "WearAirMouse"
    }

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        prefs = getSharedPreferences("wearairmouse_prefs", MODE_PRIVATE)
        
        setupUI()
        loadSettings()
    }

    private fun setupUI() {
        // Переключатель гироскопа
        binding.switchGyro.setOnCheckedChangeListener { _, isChecked ->
            saveSetting("enable_gyro", isChecked)
            Log.d(TAG, "Gyro enabled: $isChecked")
        }
        
        // Переключатель акселерометра
        binding.switchAccel.setOnCheckedChangeListener { _, isChecked ->
            saveSetting("enable_accel", isChecked)
            Log.d(TAG, "Accel enabled: $isChecked")
        }
        
        // Ползунок чувствительности
        binding.seekSensitivity.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val value = 0.5f + (progress / 100f) * 2.0f // 0.5..2.5
                    saveSetting("sensitivity", value)
                }
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })
        
        // Ползунок мёртвой зоны
        binding.seekDeadzone.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val value = progress / 100f // 0.0..0.5
                    saveSetting("deadzone", value)
                }
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })
        
        // Ползунок чувствительности жестов
        binding.seekGestureSensitivity.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val value = progress / 100f // 0.0..1.0
                    saveSetting("gesture_sensitivity", value)
                }
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })
    }

    private fun loadSettings() {
        binding.switchGyro.isChecked = prefs.getBoolean("enable_gyro", true)
        binding.switchAccel.isChecked = prefs.getBoolean("enable_accel", true)
        
        val sensitivity = prefs.getFloat("sensitivity", 1.0f)
        val sensitivityProgress = ((sensitivity - 0.5f) / 2.0f * 100).toInt().coerceIn(0, 100)
        binding.seekSensitivity.progress = sensitivityProgress
        
        val deadzone = prefs.getFloat("deadzone", 0.15f)
        val deadzoneProgress = (deadzone * 100).toInt().coerceIn(0, 50)
        binding.seekDeadzone.progress = deadzoneProgress
        
        val gestureSensitivity = prefs.getFloat("gesture_sensitivity", 0.7f)
        val gestureProgress = (gestureSensitivity * 100).toInt().coerceIn(0, 100)
        binding.seekGestureSensitivity.progress = gestureProgress
    }

    private fun saveSetting(key: String, value: Any) {
        prefs.edit().apply {
            when (value) {
                is Boolean -> putBoolean(key, value)
                is Float -> putFloat(key, value)
                is Int -> putInt(key, value)
            }
            apply()
        }
    }

    override fun onResume() {
        super.onResume()
        // Перезагружаем настройки при возврате
        loadSettings()
    }
}
