package com.example.camera2app.reference

import android.graphics.Color
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.camera2app.R

class ReferenceActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reference)

        setupCategoryTabs()

        supportFragmentManager.beginTransaction()
            .replace(R.id.referenceContainer, ReferenceFragment.newInstance(ReferenceCategory.CAFE))
            .commit()
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
