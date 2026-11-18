package com.depthware.lwp.diffuse.fragment

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.depthware.lwp.diffuse.manager.MusicPaletteManager
import com.depthware.lwp.diffuse.manager.ViewModelManager
import com.depthware.lwp.diffuse.model.MusicPaletteDisplayItem
import com.depthware.lwp.diffuse.model.MusicPaletteFile
import com.depthware.lwp.diffuse.util.DiffuseUtils
import com.depthware.lwp.diffuse.v2.R
import com.depthware.lwp.diffuse.v2.databinding.LayoutPaletteDisplayItemBinding

abstract class PaletteBaseAdapter : ListAdapter<MusicPaletteDisplayItem, PaletteBaseAdapter.Holder>(
    object : DiffUtil.ItemCallback<MusicPaletteDisplayItem>() {
        override fun areItemsTheSame(
            oldItem: MusicPaletteDisplayItem,
            newItem: MusicPaletteDisplayItem
        ) = oldItem == newItem

        override fun areContentsTheSame(
            oldItem: MusicPaletteDisplayItem,
            newItem: MusicPaletteDisplayItem
        ) = oldItem == newItem
    }
) {
    class Holder(val itemBinding: LayoutPaletteDisplayItemBinding) :
        RecyclerView.ViewHolder(itemBinding.root)

    abstract fun confirmDelete(onConfirmed: Runnable)
    abstract fun onPaletteSelected()
    abstract fun refreshData()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val b = LayoutPaletteDisplayItemBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        b.txtPaletteTitle.isSelected = true
        return Holder(b)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = getItem(position)
        val palette = item.palette
        val ctx = holder.itemBinding.root.context
        holder.itemBinding.imgPalettePreview.setImageDrawable(palette.loadDrawable())
        holder.itemBinding.txtPaletteTitle.text = palette.title
        holder.itemBinding.paletteItemCard.setCardBackgroundColor(
            ContextCompat.getColor(
                ctx,
                if (item.selected) R.color.colorOnSurface else R.color.colorSurface
            )
        )
        holder.itemBinding.txtPaletteTitle.setTextColor(
            ContextCompat.getColor(
                ctx,
                if (item.selected) R.color.colorSurface else R.color.colorOnSurface
            )
        )

        holder.itemBinding.root.isLongClickable = true
        holder.itemBinding.root.setOnClickListener {
            DiffuseUtils.setLiveDataValue(
                ViewModelManager.viewModel.defaultAlbumArtID,
                palette.id
            )
            onPaletteSelected()
        }
        holder.itemBinding.root.setOnLongClickListener {
            if (item.deletable && palette is MusicPaletteFile) {
                confirmDelete {
                    MusicPaletteManager.deletePalette(palette)
                    refreshData()
                }
            }
            true
        }
    }
}
