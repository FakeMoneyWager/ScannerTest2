package maulik.barcodescanner.ui

import android.content.ContentUris
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import maulik.barcodescanner.databinding.ItemGalleryImageBinding

class ImageThumbAdapter :
    RecyclerView.Adapter<ImageThumbAdapter.ImgVH>() {

    private val images = mutableListOf<Uri>()

    // ADD THIS LINE: A public property to hold the click listener lambda.
    var onImageClickListener: ((Uri) -> Unit)? = null

    fun submit(list: List<Uri>) { images.apply { clear(); addAll(list) }; notifyDataSetChanged() }

    override fun onCreateViewHolder(p: ViewGroup, vType: Int) =
        ImgVH(ItemGalleryImageBinding.inflate(
            LayoutInflater.from(p.context), p, false))

    override fun getItemCount() = images.size

    override fun onBindViewHolder(h: ImgVH, pos: Int) = h.bind(images[pos])

    inner class ImgVH(private val b: ItemGalleryImageBinding) :
        RecyclerView.ViewHolder(b.root) {

        fun bind(uri: Uri) {
            // tiny 120×120 thumbnail from MediaStore
            val bmp: Bitmap? = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    b.root.context.contentResolver.loadThumbnail(uri,
                        android.util.Size(120, 120), null)
                else
                    @Suppress("DEPRECATION")
                    MediaStore.Images.Thumbnails.getThumbnail(
                        b.root.context.contentResolver,
                        // Use ContentUris to safely parse ID from content URI
                        ContentUris.parseId(uri),
                        MediaStore.Images.Thumbnails.MINI_KIND, null)
            } catch (_: Exception) { null }

            b.ivThumb.setImageBitmap(bmp)

            // ADD THIS BLOCK: When an item is clicked, invoke the listener with the item's URI.
            itemView.setOnClickListener {
                onImageClickListener?.invoke(uri)
            }
        }
    }
}