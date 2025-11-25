package com.example.camera2app.reference

import com.example.camera2app.R

object ReferenceData {

    fun getImages(category: ReferenceCategory): List<Int> {
        return when (category) {

            ReferenceCategory.WINTER -> listOf(
                R.drawable.winter1,
                R.drawable.winter2
            )

            ReferenceCategory.CAFE -> listOf(
                R.drawable.cafe1
            )

            ReferenceCategory.STREET -> listOf(
                R.drawable.street1,
                R.drawable.street2
            )

            ReferenceCategory.LANDMARK -> listOf(
                R.drawable.landmark1
            )

            ReferenceCategory.MY -> emptyList()
            ReferenceCategory.HOT -> emptyList()
        }
    }
}
