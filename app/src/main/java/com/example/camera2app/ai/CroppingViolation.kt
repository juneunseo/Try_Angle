package com.example.camera2app.ai

data class CroppingViolation(
    val jointName: String,
    val position: Float,
    val severity: ViolationSeverity
)

enum class ViolationSeverity {
    CRITICAL,
    WARNING,
    MINOR
}
