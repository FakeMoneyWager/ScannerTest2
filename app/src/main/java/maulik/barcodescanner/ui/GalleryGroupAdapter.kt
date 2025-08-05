package maulik.barcodescanner.ui

import android.net.Uri
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager // Import this
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import maulik.barcodescanner.databinding.ItemGroupGalleryBinding

typealias GalleryItemGroup = Pair<String, List<Uri>>

class GalleryGroupAdapter : ListAdapter<GalleryItemGroup, GalleryGroupAdapter.GroupViewHolder>(GroupDiffCallback()) {

    var onDeleteClickListener: ((String, List<Uri>) -> Unit)? = null
    var onImageClickListener: ((Uri) -> Unit)? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GroupViewHolder {
        val binding = ItemGroupGalleryBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return GroupViewHolder(binding, onImageClickListener)
    }

    override fun onBindViewHolder(holder: GroupViewHolder, position: Int) {
        val group = getItem(position)
        holder.bind(group, onDeleteClickListener)
    }

    class GroupViewHolder(
        private val binding: ItemGroupGalleryBinding,
        private val onImageClick: ((Uri) -> Unit)?
    ) : RecyclerView.ViewHolder(binding.root) {

        private val thumbAdapter: ImageThumbAdapter?

        init {
            // --- START OF FIX ---
            // Set up the inner RecyclerView here in the init block.
            // This is more reliable than relying solely on the XML attributes.
            binding.rvImages.layoutManager = LinearLayoutManager(
                binding.root.context,
                LinearLayoutManager.HORIZONTAL, // Ensure horizontal layout
                false
            )

            // Initialize the inner adapter for thumbnails
            thumbAdapter = onImageClick?.let { listener ->
                ImageThumbAdapter(listener).also {
                    binding.rvImages.adapter = it
                }
            }
            // --- END OF FIX ---
        }

        fun bind(
            group: GalleryItemGroup,
            onDeleteClick: ((String, List<Uri>) -> Unit)?
        ) {
            val (qr, uris) = group
            binding.tvQr.text = "❌ ${qr}"
            binding.tvQr.setOnClickListener {
                onDeleteClick?.invoke(qr, uris)
            }

            // Log the URIs to confirm they are being passed correctly
            android.util.Log.d("GalleryGroupAdapter", "Binding ${uris.size} images for QR: $qr")

            // Submit the list of image URIs to the inner adapter
            thumbAdapter?.submitList(uris)
        }
    }

    class GroupDiffCallback : DiffUtil.ItemCallback<GalleryItemGroup>() {
        override fun areItemsTheSame(oldItem: GalleryItemGroup, newItem: GalleryItemGroup): Boolean {
            return oldItem.first == newItem.first
        }

        override fun areContentsTheSame(oldItem: GalleryItemGroup, newItem: GalleryItemGroup): Boolean {
            // A more robust check to ensure the list redraws if the image list changes
            return oldItem.first == newItem.first && oldItem.second == newItem.second
        }
    }

    fun getCurrentData(): List<GalleryItemGroup> {
        return currentList
    }

    fun getCurrentImageCount(): Int {
        return currentList.sumOf { it.second.size }
    }
}