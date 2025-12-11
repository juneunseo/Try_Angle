package com.example.camera2app.reference

import com.example.camera2app.R

object ReferenceData {

    fun getImages(category: ReferenceCategory): List<Int> {
        return when (category) {

            ReferenceCategory.WINTER -> listOf(
                R.drawable.winter1,
                R.drawable.winter2,
                R.drawable.winter3,
                R.drawable.winter4,
                R.drawable.winter5,
                R.drawable.winter6,
                R.drawable.winter7,
                R.drawable.winter8
            )

            ReferenceCategory.CAFE -> listOf(
                R.drawable.cafe1,
                R.drawable.cafe2,
                R.drawable.cafe3,
                R.drawable.cafe4,
                R.drawable.cafe5,
                R.drawable.cafe6,
                R.drawable.cafe7,
                R.drawable.cafe8
            )

            ReferenceCategory.STREET -> listOf(
                R.drawable.street1,
                R.drawable.street2,
                R.drawable.street3,
                R.drawable.street4,
                R.drawable.street5,
                R.drawable.street6,
                R.drawable.street7,
                R.drawable.street8
            )

            ReferenceCategory.LANDMARK -> listOf(
                R.drawable.landmark1,
                R.drawable.landmark2,
                R.drawable.landmark3,
                R.drawable.landmark4,
                R.drawable.landmark5,
                R.drawable.landmark6,
                R.drawable.landmark7,
                R.drawable.landmark8
            )

            ReferenceCategory.HOT -> listOf(
                R.drawable.hot1,
                R.drawable.hot2,
                R.drawable.hot3,
                R.drawable.hot4,
                R.drawable.hot5,
                R.drawable.hot6,
                R.drawable.hot7,
                R.drawable.hot8
            )

            ReferenceCategory.MY -> emptyList()


        }
    }
}
