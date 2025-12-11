package com.example.camera2app.ai

// MARK: - Gap 타입
enum class GapType(val rawValue: String) {
    distance("distance"),          // 거리 (앞/뒤 이동)
    positionX("positionX"),        // X 위치 (좌/우 이동)
    positionY("positionY"),        // Y 위치 (상/하 이동)
    tilt("tilt"),                  // 기울기
    faceYaw("faceYaw"),            // 얼굴 좌우 회전
    facePitch("facePitch"),        // 얼굴 상하 각도
    cameraAngle("cameraAngle"),    // 카메라 앵글
    gaze("gaze"),                  // 시선
    composition("composition"),    // 구도
    leftArm("leftArm"),            // 왼팔 포즈
    rightArm("rightArm"),          // 오른팔 포즈
    leftLeg("leftLeg"),            // 왼다리 포즈
    rightLeg("rightLeg"),          // 오른다리 포즈
    missingParts("missingParts"),  // 안 보이는 부위
    aspectRatio("aspectRatio"),    // 🆕 화면 비율
    excessivePadding("excessivePadding") // 🆕 과도한 여백
}
