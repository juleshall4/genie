package dev.genie.android.chatgpt

import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ModelCatalogCacheTest {
    @Test
    fun reusesCatalogUntilFiveMinutesThenRequiresRefresh() {
        var time = 100L
        val cache = ModelCatalogCache { time }
        val catalog = ChatGptModels(emptyList())
        assertNull(cache.get("account", "token"))
        cache.put("account", "token", catalog)
        time += 299_999
        assertSame(catalog, cache.get("account", "token"))
        time++
        assertNull(cache.get("account", "token"))
    }

    @Test
    fun neverReusesAnotherAccountOrOldCredentialsAndCanBeInvalidated() {
        val cache = ModelCatalogCache { 100L }
        val catalog = ChatGptModels(emptyList())
        cache.put("account-a", "token-a", catalog)
        assertNull(cache.get("account-b", "token-a"))
        assertNull(cache.get("account-a", "token-b"))
        assertSame(catalog, cache.get("account-a", "token-a"))
        cache.clear()
        assertNull(cache.get("account-a", "token-a"))
    }
}
