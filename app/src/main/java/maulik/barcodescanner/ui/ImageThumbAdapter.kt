package maulik.barcodescanner.ui

import android.net.Uri
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import maulik.barcodescanner.databinding.ItemImageThumbnailBinding

class ImageThumbAdapter(
    private val onImageClickListener: (Uri) -> Unit
) : ListAdapter<Uri, ImageThumbAdapter.ImageViewHolder>(ImageDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ImageViewHolder {
        val binding = ItemImageThumbnailBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ImageViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ImageViewHolder, position: Int) {
        val uri = getItem(position)
        holder.bind(uri, onImageClickListener)
    }

    class ImageViewHolder(
        private val binding: ItemImageThumbnailBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(uri: Uri, onImageClickListener: (Uri) -> Unit) {
            binding.ivThumb.load(uri) {
                crossfade(true)
                // Reference icons from the Android framework's R class
                placeholder(android.R.drawable.ic_menu_gallery)
                error(android.R.drawable.ic_dialog_alert)
            }
            binding.root.setOnClickListener {
                onImageClickListener(uri)
            }
        }
    }

    class ImageDiffCallback : DiffUtil.ItemCallback<Uri>() {
        override fun areItemsTheSame(oldItem: Uri, newItem: Uri): Boolean {
            return oldItem == newItem
        }

        override fun areContentsTheSame(oldItem: Uri, newItem: Uri): Boolean {
            return oldItem == newItem
        }
    }
}