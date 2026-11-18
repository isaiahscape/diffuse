package com.depthware.lwp.diffuse.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.depthware.lwp.diffuse.manager.MusicPaletteManager
import com.depthware.lwp.diffuse.manager.ViewModelManager
import com.depthware.lwp.diffuse.model.MusicPaletteDisplayItem
import com.depthware.lwp.diffuse.util.DiffuseUtils
import com.depthware.lwp.diffuse.v2.databinding.LayoutPaletteSelectionGradientFragmentBinding

class PaletteGradientFragment : Fragment() {

    private var _binding: LayoutPaletteSelectionGradientFragmentBinding? = null
    private val binding get() = _binding!!

    private val gridLayoutManager = StaggeredGridLayoutManager(3, StaggeredGridLayoutManager.VERTICAL)
    private val adapter = object : PaletteBaseAdapter() {
        override fun confirmDelete(onConfirmed: Runnable) = Unit
        override fun onPaletteSelected() {
            requireActivity().finish()
        }

        override fun refreshData() {
            val selectedId = DiffuseUtils.getValueOrDefault(
                ViewModelManager.viewModel.defaultAlbumArtID,
                MusicPaletteManager.DEFAULT_PALETTE_ID
            )
            submitList(
                MusicPaletteManager.gradientPalettes.map {
                    MusicPaletteDisplayItem(it, it.id == selectedId, deletable = false)
                }
            )
        }
    }

    private val focusListener = ViewTreeObserver.OnWindowFocusChangeListener {
        val ctx = context ?: return@OnWindowFocusChangeListener
        val span = (binding.paletteRecycleView.width / DiffuseUtils.dpToPx(ctx, 120.0f)).toInt()
            .coerceIn(2, 6)
        gridLayoutManager.spanCount = span
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = LayoutPaletteSelectionGradientFragmentBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.paletteRecycleView.layoutManager = gridLayoutManager
        binding.paletteRecycleView.adapter = adapter
        binding.paletteRecycleView.isNestedScrollingEnabled = true
        adapter.refreshData()
    }

    override fun onStart() {
        super.onStart()
        binding.root.viewTreeObserver.addOnWindowFocusChangeListener(focusListener)
    }

    override fun onStop() {
        binding.root.viewTreeObserver.removeOnWindowFocusChangeListener(focusListener)
        super.onStop()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
