package dev.genie.android.chatgpt

/** Short-lived catalog reuse, scoped to the exact authenticated connection. */
class ModelCatalogCache(private val now: () -> Long) {
    private data class Entry(
        val clientId: String,
        val accessToken: String,
        val catalog: ChatGptModels,
        val fetchedAt: Long
    )

    private var entry: Entry? = null

    @Synchronized
    fun get(clientId: String, accessToken: String): ChatGptModels? {
        val saved = entry ?: return null
        return saved.catalog.takeIf {
            saved.clientId == clientId && saved.accessToken == accessToken &&
                now() - saved.fetchedAt in 0 until 5 * 60_000L
        }
    }

    @Synchronized
    fun put(clientId: String, accessToken: String, catalog: ChatGptModels) {
        entry = Entry(clientId, accessToken, catalog, now())
    }

    @Synchronized
    fun clear() {
        entry = null
    }
}
