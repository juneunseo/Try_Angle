package com.example.camera2app.ai

import android.content.*
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.MutableLiveData

/**
 * ✅ 발열 & 배터리 상태 관리자 (iOS ThermalStateManager.swift 대응)
 */
class ThermalStateManager(
    private val context: Context,
    lifecycleOwner: LifecycleOwner
) {

    // ✅ 상태 LiveData (Swift @Published 대응)
    val currentThermalState = MutableLiveData<ThermalState>(ThermalState.NOMINAL)
    val isLowPowerMode = MutableLiveData(false)
    val batteryLevel = MutableLiveData(1.0f)
    val recommendedAnalysisInterval = MutableLiveData(0.016f) // 기본 60fps

    private val powerManager =
        context.getSystemService(Context.POWER_SERVICE) as PowerManager

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateBatteryLevel(intent)
        }
    }

    private val thermalReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateThermalState()
        }
    }

    init {
        setupMonitoring()
        updateRecommendedInterval()

        // LiveData 자동 반영
        currentThermalState.observe(lifecycleOwner) { updateRecommendedInterval() }
        isLowPowerMode.observe(lifecycleOwner) { updateRecommendedInterval() }
        batteryLevel.observe(lifecycleOwner) { updateRecommendedInterval() }
    }

    // ✅ 시스템 상태 감시
    private fun setupMonitoring() {

        // 🔋 배터리 감지
        context.registerReceiver(
            batteryReceiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )

        // 🔥 발열 감지 (Android 10 이상)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.registerReceiver(
                thermalReceiver,
                IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            )
        }

        updateThermalState()
        updatePowerState()
    }

    // ✅ 발열 상태 업데이트
    private fun updateThermalState() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {

            val state = when (powerManager.currentThermalStatus) {
                PowerManager.THERMAL_STATUS_NONE -> ThermalState.NOMINAL
                PowerManager.THERMAL_STATUS_LIGHT -> ThermalState.FAIR
                PowerManager.THERMAL_STATUS_MODERATE -> ThermalState.SERIOUS
                PowerManager.THERMAL_STATUS_SEVERE -> ThermalState.CRITICAL
                PowerManager.THERMAL_STATUS_CRITICAL -> ThermalState.CRITICAL
                PowerManager.THERMAL_STATUS_EMERGENCY -> ThermalState.CRITICAL
                PowerManager.THERMAL_STATUS_SHUTDOWN -> ThermalState.CRITICAL
                else -> ThermalState.NOMINAL
            }

            currentThermalState.postValue(state)
            logThermalState(state)

        } else {
            // ✅ Android 9 이하에서는 발열 정보 없음 → 기본값
            currentThermalState.postValue(ThermalState.NOMINAL)
        }
    }


    // ✅ 저전력 모드 업데이트
    private fun updatePowerState() {
        val isPowerSave = powerManager.isPowerSaveMode
        isLowPowerMode.postValue(isPowerSave)
        println("🔋 저전력 모드: ${if (isPowerSave) "ON" else "OFF"}")
    }

    // ✅ 배터리 레벨 업데이트
    private fun updateBatteryLevel(intent: Intent?) {
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: return
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)

        if (scale > 0) {
            val ratio = level.toFloat() / scale.toFloat()
            batteryLevel.postValue(ratio)
        }
    }

    // ✅ 권장 분석 주기 계산 (Swift 로직 그대로)
    private fun updateRecommendedInterval() {

        val thermal = currentThermalState.value ?: ThermalState.NOMINAL
        val lowPower = isLowPowerMode.value ?: false
        val battery = batteryLevel.value ?: 1.0f

        val baseInterval = when (thermal) {
            ThermalState.NOMINAL -> 0.016f   // 60fps
            ThermalState.FAIR -> 0.016f
            ThermalState.SERIOUS -> 0.022f   // 45fps
            ThermalState.CRITICAL -> 0.033f  // 30fps
        }

        val finalInterval =
            if (lowPower || battery < 0.2f) {
                maxOf(baseInterval, 0.022f)
            } else {
                baseInterval
            }

        recommendedAnalysisInterval.postValue(finalInterval)
    }

    // ✅ 발열 상태 로그
    private fun logThermalState(state: ThermalState) {
        val (emoji, name) = when (state) {
            ThermalState.NOMINAL -> "❄️" to "정상"
            ThermalState.FAIR -> "☁️" to "약간 따뜻"
            ThermalState.SERIOUS -> "🔥" to "뜨거움"
            ThermalState.CRITICAL -> "🚨" to "매우 뜨거움"
        }

        val intervalMs = ((recommendedAnalysisInterval.value ?: 0.033f) * 1000).toInt()
        println("$emoji 발열 상태: $name → 권장 간격: ${intervalMs}ms")
    }

    // ✅ 분석 실행 허용 여부
    fun shouldPerformAnalysis(): Boolean {
        return true
    }

    // ✅ CoreML Flags → Android NNAPI 대응
    fun getNNAPIFlags(): Int {
        if (isLowPowerMode.value == true ||
            currentThermalState.value == ThermalState.SERIOUS ||
            currentThermalState.value == ThermalState.CRITICAL
        ) {
            return 1 // NNAPI 저전력 모드 사용
        }
        return 0
    }
}
