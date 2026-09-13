package com.devtool.gpsmocker.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.devtool.gpsmocker.databinding.ItemLandmarkBinding
import com.devtool.gpsmocker.db.LandmarkEntity

class LandmarkAdapter(
    private val onDelete: (LandmarkEntity) -> Unit
) : ListAdapter<LandmarkEntity, LandmarkAdapter.VH>(DIFF) {

    inner class VH(val b: ItemLandmarkBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(p: ViewGroup, t: Int) =
        VH(ItemLandmarkBinding.inflate(LayoutInflater.from(p.context), p, false))

    override fun onBindViewHolder(h: VH, pos: Int) {
        val lm = getItem(pos)
        h.b.tvName.text = lm.name
        h.b.tvMeta.text = listOfNotNull(
            lm.continent.takeIf { it.isNotEmpty() },
            lm.category.takeIf { it.isNotEmpty() },
            "${"%.4f".format(lm.lat)}, ${"%.4f".format(lm.lon)}"
        ).joinToString(" · ")
        h.b.btnDelete.setOnClickListener { onDelete(lm) }
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<LandmarkEntity>() {
            override fun areItemsTheSame(a: LandmarkEntity, b: LandmarkEntity) = a.id == b.id
            override fun areContentsTheSame(a: LandmarkEntity, b: LandmarkEntity) = a == b
        }
    }
}
