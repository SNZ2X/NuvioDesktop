package com.nuvio.app.features.livetv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LiveTvResetOrderingTest {
    @Test
    fun replacementRepositoryWaitsForOldCredentialDeletion() = runBlocking {
        val clearing = CountDownLatch(1)
        val finishClearing = CountDownLatch(1)
        val constructionStarted = CountDownLatch(1)
        val constructionFinished = CountDownLatch(1)
        val loader = object : LiveTvDocumentLoader {
            override suspend fun load(location: String, maxBytes: Int, allowLocalFiles: Boolean): LiveTvDocument =
                error("Disabled sources must not be fetched")
        }
        val oldStore = object : LiveTvSourceStore, LiveTvClearableSourceStore {
            override fun load() = emptyList<LiveTvSource>()
            override fun save(sources: List<LiveTvSource>) = Unit
            override fun clear() {
                clearing.countDown()
                check(finishClearing.await(5, TimeUnit.SECONDS))
            }
        }
        val old = LiveTvRepository(oldStore, loader)
        old.load()
        val replacementStore = object : LiveTvSourceStore {
            var values = emptyList<LiveTvSource>()
            override fun load() = values
            override fun save(sources: List<LiveTvSource>) { values = sources }
        }

        val wipe = launch(Dispatchers.IO) { LiveTvRepository.clearAll() }
        assertTrue(clearing.await(5, TimeUnit.SECONDS))
        val replacement = async(Dispatchers.IO) {
            constructionStarted.countDown()
            LiveTvRepository(replacementStore, loader).also { constructionFinished.countDown() }
        }
        try {
            assertTrue(constructionStarted.await(5, TimeUnit.SECONDS))
            assertFalse(
                constructionFinished.await(150, TimeUnit.MILLISECONDS),
                "New repositories must wait until old source files have been deleted",
            )
        } finally {
            finishClearing.countDown()
        }
        withTimeout(5_000) { wipe.join() }
        val repo = withTimeout(5_000) { replacement.await() }
        val source = LiveTvSource("new", "New", LiveTvSourceKind.M3U_URL, "https://example.test/list.m3u", enabled = false)
        repo.saveSource(source)
        assertEquals(listOf(source), replacementStore.values)
        assertEquals(listOf(source), repo.uiState.value.sources)
    }
}
