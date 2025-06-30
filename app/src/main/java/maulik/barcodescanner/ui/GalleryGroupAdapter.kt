package maulik.barcodescanner.ui

import android.net.Uri
import android.view.ViewGroup
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import maulik.barcodescanner.databinding.ItemGalleryGroupBinding

class GalleryGroupAdapter :
    RecyclerView.Adapter<GalleryGroupAdapter.GroupVH>() {

    private val data = mutableListOf<Pair<String, List<Uri>>>()
    var onDeleteClickListener: ((qr: String, uris: List<Uri>) -> Unit)? = null
    var onImageClickListener: ((uri: Uri) -> Unit)? = null // Forwards clicks to the activity

    fun submit(groups: List<Pair<String, List<Uri>>>) {
        data.clear()
        data.addAll(groups)
        notifyDataSetChanged()
    }

    // NEW HELPER FUNCTION
    fun getCurrentImageCount(): Int {
        return data.sumOf { it.second.size }
    }

    // NEW HELPER FUNCTION
    fun getCurrentData(): List<Pair<String, List<Uri>>> {
        return data.toList()
    }

    override fun onCreateViewHolder(p: ViewGroup, vType: Int) =
        GroupVH(ItemGalleryGroupBinding.inflate(android.view.LayoutInflater.from(p.context), p, false))

    override fun getItemCount() = data.size

    override fun onBindViewHolder(h: GroupVH, pos: Int) {
        val (qr, uris) = data[pos]
        // Pass the data and the click listener to the ViewHolder
        h.bind(qr, uris, onImageClickListener)
    }

    inner class GroupVH(private val b: ItemGalleryGroupBinding) :
        RecyclerView.ViewHolder(b.root) {

        private val imgAdapter = ImageThumbAdapter()

        init {
            b.rvImages.apply {
                adapter = imgAdapter
                layoutManager = GridLayoutManager(context, 3, RecyclerView.VERTICAL, false)
            }
        }

        fun bind(qr: String, uris: List<Uri>, imageClickListener: ((Uri) -> Unit)?) {
            b.tvQr.text = qr

            // Set the listener on the inner adapter
            imgAdapter.onImageClickListener = imageClickListener
            imgAdapter.submit(uris)

            b.btnDeleteGroup.setOnClickListener {
                onDeleteClickListener?.invoke(qr, uris)
            }
        }
    }
}