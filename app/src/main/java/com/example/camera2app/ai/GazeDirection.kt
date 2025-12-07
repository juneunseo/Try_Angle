package com.example.camera2app.ai

enum class GazeDirection(val description: String) {
    LOOKING_AT_CAMERA("카메라 응시"),
    LOOKING_LEFT("왼쪽 응시"),
    LOOKING_RIGHT("오른쪽 응시"),
    LOOKING_UP("위쪽 응시"),
    LOOKING_DOWN("아래쪽 응시"),
    LOOKING_LEFT_UP("왼쪽 위 응시"),
    LOOKING_LEFT_DOWN("왼쪽 아래 응시"),
    LOOKING_RIGHT_UP("오른쪽 위 응시"),
    LOOKING_RIGHT_DOWN("오른쪽 아래 응시"),
    UNKNOWN("알 수 없음")
}
