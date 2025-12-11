package com.example.camera2app.reference

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.camera2app.R

class ReferenceActivity : AppCompatActivity() {

    companion object {
        const val REQUEST_IMAGE_DETAIL = 3001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reference)

        // ★ LikeManager 초기화
        LikeManager.init(this)

        setupCategoryTabs()

        // 기본 탭을 My로 변경 (또는 원하는 탭)
        supportFragmentManager.beginTransaction()
            .replace(R.id.referenceContainer, ReferenceFragment.newInstance(ReferenceCategory.MY))
            .commit()
    }

    // ✅ ImageDetailActivity의 결과를 받아서 MainActivity로 전달
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_IMAGE_DETAIL && resultCode == Activity.RESULT_OK) {

            setResult(Activity.RESULT_OK, data)

            // 🔥 페이드/전환 애니메이션 제거 (중간 화면 깜빡임 방지)
            overridePendingTransition(0, 0)

            finish()
        }
    }


    private fun setupCategoryTabs() {
        val container = findViewById<LinearLayout>(R.id.categoryBar)

        ReferenceCategory.values().forEach { cat ->
            val tv = TextView(this).apply {
                text = cat.title
                setPadding(20, 10, 20, 10)
                setTextColor(Color.BLACK)
                textSize = 16f

                setOnClickListener {
                    supportFragmentManager.beginTransaction()
                        .replace(R.id.referenceContainer, ReferenceFragment.newInstance(cat))
                        .commit()
                }
            }
            container.addView(tv)
        }
    }
}