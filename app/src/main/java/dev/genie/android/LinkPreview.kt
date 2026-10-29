package dev.genie.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.text.Html
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** What the webpage card shows: read from the page's Open Graph and icon tags. */
data class LinkPreview(val title: String?, val description: String?, val image: Bitmap?, val icon: Bitmap?)

object LinkPreviews {
    private val http = OkHttpClient.Builder().callTimeout(8, TimeUnit.SECONDS).build()
    private val main = Handler(Looper.getMainLooper())
    private val meta = Regex("<meta\\s[^>]*>", RegexOption.IGNORE_CASE)
    private val link = Regex("<link\\s[^>]*>", RegexOption.IGNORE_CASE)
    private val attribute = Regex("([a-zA-Z:-]+)\\s*=\\s*(\"[^\"]*\"|'[^']*')")
    private val titleTag = Regex("<title[^>]*>([^<]*)</title>", RegexOption.IGNORE_CASE)

    /** Loads the preview off the main thread; the returned function cancels it. */
    fun load(url: String, onLoaded: (LinkPreview) -> Unit): () -> Unit {
        val page = url.toHttpUrlOrNull() ?: return {}
        val calls = mutableListOf<Call>()
        var cancelled = false
        thread(name = "link-preview") {
            val preview = runCatching { fetch(page) { call -> synchronized(calls) { calls += call } } }.getOrNull()
            if (preview != null) main.post { if (!cancelled) onLoaded(preview) }
        }
        return {
            cancelled = true
            synchronized(calls) { calls.forEach(Call::cancel) }
        }
    }

    private fun fetch(page: HttpUrl, track: (Call) -> Unit): LinkPreview {
        val html = get(page, track)?.use { it.body?.source()?.let { body ->
            body.request(256 * 1024)
            body.buffer.snapshot().utf8()
        } }.orEmpty()
        val tags = meta.findAll(html).map { attributes(it.value) }.toList()
        fun property(vararg names: String) = names.firstNotNullOfOrNull { name ->
            tags.firstOrNull { (it["property"] ?: it["name"]).equals(name, true) }?.get("content")?.let(::clean)
        }?.takeIf { it.isNotBlank() }
        val icons = link.findAll(html).map { attributes(it.value) }
            .filter { it["rel"]?.split(' ')?.any { rel -> rel.equals("icon", true) || rel.equals("apple-touch-icon", true) } == true }
            .mapNotNull { it["href"] }.toList()
        val title = property("og:title", "twitter:title") ?: titleTag.find(html)?.groupValues?.get(1)?.let(::clean)
        val image = property("og:image", "og:image:url", "twitter:image")?.let(page::resolve)
        val icon = (icons.firstNotNullOfOrNull { page.resolve(it) } ?: page.resolve("/favicon.ico"))
        return LinkPreview(title?.takeIf { it.isNotBlank() }, property("og:description", "description", "twitter:description"),
            image?.let { bitmap(it, track) }, icon?.let { bitmap(it, track) })
    }

    private fun get(url: HttpUrl, track: (Call) -> Unit): Response? {
        val response = http.newCall(Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android) Genie").build()).also(track).execute()
        if (response.isSuccessful) return response
        response.close()
        return null
    }

    private fun bitmap(url: HttpUrl, track: (Call) -> Unit): Bitmap? = runCatching {
        get(url, track)?.use { response -> response.body?.bytes()?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } }
    }.getOrNull()

    private fun attributes(tag: String): Map<String, String> = attribute.findAll(tag)
        .associate { it.groupValues[1].lowercase() to it.groupValues[2].drop(1).dropLast(1) }

    private fun clean(text: String): String =
        Html.fromHtml(text, Html.FROM_HTML_MODE_LEGACY).toString().replace(Regex("\\s+"), " ").trim()
}
