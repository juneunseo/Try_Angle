package com.example.camera2app.ai

class AdaptiveFrameSkipper {

    // ✅ 지금은 LiveData 없이 "순수 enum 상태"만 사용
    var currentThermalState: ThermalState = ThermalState.NOMINAL

    /**
     * 프레임 스킵 간격 반환
     * Triple(first, second, third)
     * 1단계, 2단계, 3단계 AI 처리 프레임 간격
     */
    fun getFrameIntervals(): Triple<Int, Int, Int> {
        return when (currentThermalState) {
            ThermalState.NOMINAL -> Triple(1, 5, 30)
            ThermalState.FAIR -> Triple(1, 5, 30)
            ThermalState.SERIOUS -> Triple(2, 10, 60)
            ThermalState.CRITICAL -> Triple(3, 15, 90)
        }
    }

    /**
     * 현재 프레임에서 AI 실행 여부 판단
     */
    fun shouldExecute(level: Int, frameCount: Int): Boolean {
        val intervals = getFrameIntervals()

        return when (level) {
            1 -> frameCount % intervals.first == 0
            2 -> frameCount % intervals.second == 0
            3 -> frameCount % intervals.third == 0
            else -> true
        }
    }
}
