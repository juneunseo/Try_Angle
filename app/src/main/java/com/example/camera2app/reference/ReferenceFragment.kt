package com.example.camera2app.reference

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.camera2app.R

class ReferenceFragment : Fragment() {

    private lateinit var category: ReferenceCategory
    private lateinit var recycler: RecyclerView
    private lateinit var adapter: ReferenceImageAdapter   // 🔥 여기에 사용할 클래스

    companion object {
        private const val ARG_CATEGORY = "category"

        fun newInstance(category: ReferenceCategory): ReferenceFragment {
            val fragment = ReferenceFragment()
            val args = Bundle()
            args.putString(ARG_CATEGORY, category.name)
            fragment.arguments = args
            return fragment
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val name = arguments?.getString(ARG_CATEGORY)
        category = ReferenceCategory.valueOf(name!!)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_reference, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        recycler = view.findViewById(R.id.referenceRecycler)
        recycler.layoutManager = GridLayoutManager(requireContext(), 2)

        val images = ReferenceData.getImages(category)   // 이미지 목록 받아오기
        adapter = ReferenceImageAdapter(images)
        recycler.adapter = adapter
    }
}
