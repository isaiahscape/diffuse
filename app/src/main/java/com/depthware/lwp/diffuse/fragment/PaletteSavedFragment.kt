package com.depthware.lwp.diffuse.fragment

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.depthware.lwp.diffuse.manager.ExternalNavigator
import com.depthware.lwp.diffuse.manager.MusicPaletteManager
import com.depthware.lwp.diffuse.manager.ViewModelManager
import com.depthware.lwp.diffuse.model.MusicPaletteDisplayItem
import com.depthware.lwp.diffuse.util.DiffuseUtils
import com.depthware.lwp.diffuse.v2.R
import com.depthware.lwp.diffuse.v2.databinding.LayoutPaletteSelectionSavedFragmentBinding
import com.depthware.lwp.diffuse.view.CropImageDialogView
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class PaletteSavedFragment : Fragment() {

    companion object {
        private const val REQUEST_PICK_IMAGE = 0
    }

    private var _binding: LayoutPaletteSelectionSavedFragmentBinding? = null
    private val binding get() = _binding!!

    private val gridLayoutManager = StaggeredGridLayoutManager(3, StaggeredGridLayoutManager.VERTICAL)
    private val adapter = object : PaletteBaseAdapter() {
        override fun confirmDelete(onConfirmed: Runnable) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.label_delete_palette_confirm)
                .setNegativeButton(R.string.button_agree) { _, _ -> onConfirmed.run() }
                .setNeutralButton(R.string.button_cancel) { _, _ -> }
                .show()
        }

        override fun onPaletteSelected() {
            requireActivity().finish()
        }

        override fun refreshData() {
            val selectedId = DiffuseUtils.getValueOrDefault(
                ViewModelManager.viewModel.defaultAlbumArtID,
                MusicPaletteManager.DEFAULT_PALETTE_ID
            )
            submitList(
                MusicPaletteManager.savedPalettes.map {
                    MusicPaletteDisplayItem(it, it.id == selectedId, deletable = true)
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
        _binding = LayoutPaletteSelectionSavedFragmentBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val vm = ViewModelManager.viewModel

        binding.paletteRecycleView.layoutManager = gridLayoutManager
        binding.paletteRecycleView.adapter = adapter
        binding.paletteRecycleView.isNestedScrollingEnabled = true
        adapter.refreshData()

        vm.isPlaying.observe(viewLifecycleOwner) { playing ->
            val isPlaying = playing == true
            binding.btnGetCuratedArt.visibility = if (isPlaying) View.GONE else View.VISIBLE
            binding.btnSaveCurrentPlayingArt.visibility = if (isPlaying) View.VISIBLE else View.GONE
        }

        binding.btnGetCuratedArt.setOnClickListener {
            ExternalNavigator.openCuratedPlaylist()
        }
        binding.btnSaveCurrentPlayingArt.setOnClickListener {
            val art = DiffuseUtils.getValueOrDefault(vm.playingAlbumArt, null)
            if (art != null) {
                val id = MusicPaletteManager.savePalette(art)
                DiffuseUtils.setLiveDataValue(vm.defaultAlbumArtID, id, post = true)
                adapter.refreshData()
            }
        }
        binding.btnUploadCustomPalette.setOnClickListener {
            openImagePicker()
        }
    }

    @Suppress("DEPRECATION")
    private fun openImagePicker() {
        val getContent = Intent(Intent.ACTION_GET_CONTENT).apply { type = "image/*" }
        val pick = Intent(Intent.ACTION_PICK).apply {
            setDataAndType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*")
        }
        val chooser = Intent.createChooser(getContent, "Select Image").apply {
            putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(pick))
        }
        startActivityForResult(chooser, REQUEST_PICK_IMAGE)
    }

    private fun showCropDialog(uri: Uri) {
        val cropView = CropImageDialogView(requireContext(), uri)
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setNegativeButton(R.string.button_set) { _, _ ->
                val cropped = cropView.getCroppedBitmap()
                if (cropped != null) {
                    val id = MusicPaletteManager.savePalette(cropped)
                    DiffuseUtils.setLiveDataValue(
                        ViewModelManager.viewModel.defaultAlbumArtID,
                        id,
                        post = true
                    )
                    adapter.refreshData()
                }
            }
            .setPositiveButton(R.string.button_cancel) { _, _ -> }
            .create()
        dialog.setView(cropView)
        dialog.show()
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        DiffuseUtils.logDebug("onActivityResult requestCode $requestCode resultCode $resultCode")
        if (resultCode == Activity.RESULT_OK && requestCode == REQUEST_PICK_IMAGE && data?.data != null) {
            showCropDialog(data.data!!)
        }
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
