package dev.genie.android

import android.view.View
import android.widget.ImageView
import android.widget.TextView
import dev.genie.android.chatgpt.WebCitation
import java.net.URI

/** The webpage card under an answer: the first cited page, with its title, site, and preview image. */
class LinkCard(private val card: View, private val open: (String) -> Unit) {
    private var cancelPreview: () -> Unit = {}
    var url: String? = null
        private set

    init {
        card.setOnClickListener { url?.let(open) }
        card.findViewById<View>(R.id.link_thumbnail).clipToOutline = true
        (card.findViewById<View>(R.id.link_favicon).parent as View).clipToOutline = true
        clear()
    }

    fun setSources(citations: List<WebCitation>) {
        val source = citations.firstOrNull {
            val uri = runCatching { URI(it.url) }.getOrNull()
            uri?.scheme in setOf("http", "https") && !uri?.host.isNullOrBlank()
        } ?: return clear()
        if (source.url == url) return
        cancelPreview()
        url = source.url
        val site = URI(source.url).host.removePrefix("www.")
        card.findViewById<TextView>(R.id.link_domain).text = site
        card.findViewById<TextView>(R.id.link_favicon_letter).text = site.take(1).uppercase()
        card.findViewById<TextView>(R.id.link_title).text = source.title.ifBlank { site }
        card.findViewById<TextView>(R.id.link_description).visibility = View.GONE
        card.findViewById<ImageView>(R.id.link_thumbnail).setImageDrawable(null)
        card.findViewById<ImageView>(R.id.link_favicon).visibility = View.GONE
        card.contentDescription = card.context.getString(R.string.open_webpage, source.title.ifBlank { site }, site)
        card.visibility = View.VISIBLE
        cancelPreview = LinkPreviews.load(source.url) { preview ->
            if (source.title.isBlank()) preview.title?.let { card.findViewById<TextView>(R.id.link_title).text = it }
            preview.description?.let {
                card.findViewById<TextView>(R.id.link_description).apply { text = it; visibility = View.VISIBLE }
            }
            preview.image?.let { card.findViewById<ImageView>(R.id.link_thumbnail).setImageBitmap(it) }
            preview.icon?.let {
                card.findViewById<ImageView>(R.id.link_favicon).apply { setImageBitmap(it); visibility = View.VISIBLE }
            }
        }
    }

    fun clear() {
        cancelPreview()
        cancelPreview = {}
        url = null
        card.visibility = View.GONE
    }
}
