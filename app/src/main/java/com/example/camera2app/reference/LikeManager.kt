package com.example.camera2app.reference

import android.content.Context
import android.content.SharedPreferences

object LikeManager {
    private const val PREF_NAME = "liked_images"
    private const val KEY_LIKED = "liked_set"

    private lateinit var prefs: SharedPreferences
    private val likedSet = mutableSetOf<Int>()

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        // 저장된 좋아요 불러오기
        val saved = prefs.getStringSet(KEY_LIKED, emptySet()) ?: emptySet()
        likedSet.clear()
        likedSet.addAll(saved.mapNotNull { it.toIntOrNull() })
    }

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

    private fun save() {
        prefs.edit()
            .putStringSet(KEY_LIKED, likedSet.map { it.toString() }.toSet())
            .apply()
    }
}