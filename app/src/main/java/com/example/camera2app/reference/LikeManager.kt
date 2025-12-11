package com.example.camera2app.reference

import android.content.Context
import android.content.SharedPreferences

object LikeManager {
    private const val PREF_NAME = "liked_images"
    private const val KEY_LIKED = "liked_set"
    private const val KEY_LIKED_URIS = "liked_uris_set"  // ⭐ 새로 추가

    private lateinit var prefs: SharedPreferences
    private val likedSet = mutableSetOf<Int>()
    private val likedUris = mutableSetOf<String>()  // ⭐ 새로 추가

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

        // 저장된 좋아요 불러오기 (리소스 ID)
        val saved = prefs.getStringSet(KEY_LIKED, emptySet()) ?: emptySet()
        likedSet.clear()
        likedSet.addAll(saved.mapNotNull { it.toIntOrNull() })

        // ⭐ 저장된 좋아요 불러오기 (URI)
        val savedUris = prefs.getStringSet(KEY_LIKED_URIS, emptySet()) ?: emptySet()
        likedUris.clear()
        likedUris.addAll(savedUris)
    }

    // ========== 기존 메서드 (리소스 ID용) ==========
    fun isLiked(imageRes: Int): Boolean = likedSet.contains(imageRes)

    fun toggleLike(imageRes: Int): Boolean {
        val nowLiked = if (likedSet.contains(imageRes)) {
            likedSet.remove(imageRes)
            false
        } else {
            likedSet.add(imageRes)
            true
        }
        save()
        return nowLiked
    }

    fun getLikedImages(): List<Int> = likedSet.toList()

    // ========== 새로 추가된 메서드 (URI용) ==========

    /**
     * URI가 좋아요 목록에 있는지 확인
     */
    fun isLiked(uri: String): Boolean = likedUris.contains(uri)

    /**
     * URI 좋아요 토글 (추가/제거)
     * @return true: 좋아요 추가됨, false: 좋아요 제거됨
     */
    fun toggleLike(uri: String): Boolean {
        val nowLiked = if (likedUris.contains(uri)) {
            likedUris.remove(uri)
            false
        } else {
            likedUris.add(uri)
            true
        }
        save()
        return nowLiked
    }

    /**
     * URI를 좋아요 목록에 추가 (이미 있으면 무시)
     */
    fun addLike(uri: String) {
        if (!likedUris.contains(uri)) {
            likedUris.add(uri)
            save()
        }
    }

    /**
     * URI를 좋아요 목록에서 제거
     */
    fun removeLike(uri: String) {
        if (likedUris.remove(uri)) {
            save()
        }
    }

    /**
     * 좋아요한 모든 URI 목록 가져오기
     */
    fun getLikedUris(): List<String> = likedUris.toList()

    /**
     * 좋아요 목록 저장
     */
    private fun save() {
        prefs.edit()
            .putStringSet(KEY_LIKED, likedSet.map { it.toString() }.toSet())
            .putStringSet(KEY_LIKED_URIS, likedUris.toSet())  // ⭐ URI도 저장
            .apply()
    }
}