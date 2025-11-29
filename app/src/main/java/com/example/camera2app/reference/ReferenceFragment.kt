package com.example.camera2app.reference

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.camera2app.R

class ReferenceFragment : Fragment() {

    private lateinit var category: ReferenceCategory
    private var adapter: ReferenceImageAdapter? = null
    private var recyclerView: RecyclerView? = null
    private var emptyView: TextView? = null

    companion object {
        private const val ARG_CATEGORY = "category"

        fun newInstance(category: ReferenceCategory): ReferenceFragment {
            return ReferenceFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_CATEGORY, category.name)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        category = ReferenceCategory.valueOf(
            arguments?.getString(ARG_CATEGORY) ?: ReferenceCategory.HOT.name
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_reference, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        recyclerView = view.findViewById(R.id.recyclerView)
        emptyView = view.findViewById(R.id.emptyView)

        recyclerView?.layoutManager = GridLayoutManager(context, 2)

        loadImages()
    }

    override fun onResume() {
        super.onResume()
        // ⭐ My 탭은 돌아올 때마다 새로고침
        if (category == ReferenceCategory.MY) {
            loadImages()
        }
    }

    private fun loadImages() {
        val images = when (category) {
            ReferenceCategory.MY -> getMyImages()  // ⭐ 변경!
            ReferenceCategory.HOT -> getHotImages().map { ReferenceImage.ResourceImage(it) }
            ReferenceCategory.CAFE -> getCafeImages().map { ReferenceImage.ResourceImage(it) }
            ReferenceCategory.WINTER -> getWinterImages().map { ReferenceImage.ResourceImage(it) }
            ReferenceCategory.STREET -> getStreetImages().map { ReferenceImage.ResourceImage(it) }
            ReferenceCategory.LANDMARK -> getLandmarkImages().map { ReferenceImage.ResourceImage(it) }
        }

        // My 탭이고 비어있으면 emptyView 표시
        if (category == ReferenceCategory.MY && images.isEmpty()) {
            recyclerView?.visibility = View.GONE
            emptyView?.visibility = View.VISIBLE
            emptyView?.text = "아직 저장한 사진이 없어요\n\n마음에 드는 사진을 골라주세요"
        } else {
            recyclerView?.visibility = View.VISIBLE
            emptyView?.visibility = View.GONE

            adapter = ReferenceImageAdapter(images) {
                // My 탭에서 좋아요 해제 시 목록 갱신
                if (category == ReferenceCategory.MY) {
                    loadImages()
                }
            }
            recyclerView?.adapter = adapter
        }
    }

    // ⭐ My 탭: Int (drawable) + String (URI) 둘 다 로드
    private fun getMyImages(): List<ReferenceImage> {
        val result = mutableListOf<ReferenceImage>()

        // 1. drawable 리소스로 좋아요한 이미지
        val likedResources = LikeManager.getLikedImages()
        result.addAll(likedResources.map { ReferenceImage.ResourceImage(it) })

        // 2. URI로 좋아요한 이미지 (갤러리에서 추가)
        val likedUris = LikeManager.getLikedUris()
        result.addAll(likedUris.map { ReferenceImage.UriImage(it) })

        return result
    }

    // 각 카테고리별 이미지 리소스 (예시)

    private fun getHotImages() = listOf(
        R.drawable.hot1,
        R.drawable.hot2,
        R.drawable.hot3,
        R.drawable.hot4,
        R.drawable.hot5,
        R.drawable.hot6,
        R.drawable.hot7,
        R.drawable.hot8
    )

    private fun getCafeImages() = listOf(
        R.drawable.cafe1,
        R.drawable.cafe2,
        R.drawable.cafe3,
        R.drawable.cafe4,
        R.drawable.cafe5,
        R.drawable.cafe6,
        R.drawable.cafe7,
        R.drawable.cafe8
    )

    private fun getWinterImages() = listOf(
        R.drawable.winter1,
        R.drawable.winter2,
        R.drawable.winter3,
        R.drawable.winter4,
        R.drawable.winter5,
        R.drawable.winter6,
        R.drawable.winter7,
        R.drawable.winter8
    )

    private fun getStreetImages() = listOf(
        R.drawable.street1,
        R.drawable.street2,
        R.drawable.street3,
        R.drawable.street4,
        R.drawable.street5,
        R.drawable.street6,
        R.drawable.street7,
        R.drawable.street8
    )

    private fun getLandmarkImages() = listOf(
        R.drawable.landmark1,
        R.drawable.landmark2,
        R.drawable.landmark3,
        R.drawable.landmark4,
        R.drawable.landmark5,
        R.drawable.landmark6,
        R.drawable.landmark7,
        R.drawable.landmark8
    )
}