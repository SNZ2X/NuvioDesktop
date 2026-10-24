package com.nuvio.app.features.livetv

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioTheme
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LiveTvScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val source = LiveTvSource("demo", "Demo playlist", LiveTvSourceKind.M3U_URL, "https://demo.test/list.m3u")
    private val launches = mutableListOf<LiveTvPlaybackRequest>()

    @Test
    fun browseSearchGuideAndLaunchUseTheImportedChannels() {
        showScreen()
        compose.onNodeWithText("News One").assertIsDisplayed()
        compose.onNodeWithText("Cinema One").assertIsDisplayed()
        savePreview("channels")

        compose.onNodeWithTag("live-tv-channel-search").performTextInput("Cinema")
        compose.onNodeWithText("News One").assertDoesNotExist()
        compose.onNodeWithText("Cinema One").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(1, launches.size)
            assertEquals(listOf(launches.single().channelId), launches.single().channelIds)
        }

        compose.onNodeWithTag("live-tv-channel-search").performTextClearance()
        compose.onNodeWithText("Guide").performClick()
        compose.onNodeWithText("World Report").assertIsDisplayed()
        savePreview("guide")
    }

    @Test
    fun sourceManagerShowsNamesWithoutDisplayingSavedPlaylistCredentials() {
        showScreen()
        compose.onNodeWithContentDescription("Manage sources").performClick()
        val labels = compose.onAllNodesWithText("Demo playlist")
        labels[labels.fetchSemanticsNodes().lastIndex].assertIsDisplayed()
        compose.onNodeWithText(source.location, substring = true).assertDoesNotExist()
        savePreview("sources")
    }

    @Test
    fun sourceEditorValidatesRequiredNameAndMasksXtreamPassword() {
        showScreen()
        compose.onNodeWithContentDescription("Manage sources").performClick()
        compose.onNodeWithText("Add source").performClick()
        compose.onNodeWithText("Save").performClick()
        compose.onNodeWithText("Enter a name for this source.").assertIsDisplayed()
        compose.onNodeWithText("Xtream").performClick()
        val password = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        password.performTextInput("demo-secret")
        val rendered = password.fetchSemanticsNode().config[SemanticsProperties.EditableText].text
        assertEquals("demo-secret".length, rendered.length)
        assertTrue(rendered.all { it == '\u2022' })
        savePreview("source-editor")
    }

    @Test
    fun groupListFiltersChannelLaunchContext() {
        showScreen()
        compose.onNodeWithText("All sources").assertDoesNotExist()
        compose.onNodeWithText("All groups").assertDoesNotExist()
        compose.onNodeWithText("News").performClick()
        compose.onNodeWithText("Cinema One").assertDoesNotExist()
        compose.onNodeWithText("News One").performClick()
        compose.runOnIdle {
            assertEquals(1, launches.size)
            assertEquals(listOf(launches.single().channelId), launches.single().channelIds)
        }
        compose.onNodeWithText("Demo playlist").performClick()
        compose.onNodeWithText("Cinema One").assertIsDisplayed()
    }

    @Test
    fun hidingTheSelectedGroupExcludesItFromBrowseGuideAndSwitching() {
        showScreen()
        compose.onNodeWithText("News").performClick()
        compose.onNodeWithContentDescription("Manage groups").performClick()
        compose.onNode(hasText("News") and hasAnyAncestor(hasTestTag("live-tv-group-dialog"))).performClick()
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithText("News").assertDoesNotExist()
        compose.onNodeWithText("News One").assertDoesNotExist()
        compose.onNodeWithText("Cinema One").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(listOf(launches.single().channelId), launches.single().channelIds)
        }
        compose.onNodeWithText("Guide").performClick()
        compose.onNodeWithText("World Report").assertDoesNotExist()
        compose.onNodeWithText("Evening Cinema").assertIsDisplayed()
        savePreview("hidden-groups")
    }

    @Test
    fun hideAllKeepsGroupManagementReachableAndCanRestoreTheList() {
        showScreen()
        compose.onNodeWithContentDescription("Manage groups").performClick()
        savePreview("groups")
        compose.onNodeWithText("Hide all").performClick()
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithText("No visible groups").assertIsDisplayed()
        compose.onNodeWithText("News One").assertDoesNotExist()
        compose.onNodeWithContentDescription("Manage groups").performClick()
        compose.onNodeWithText("Select all").performClick()
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithText("News One").assertIsDisplayed()
        compose.onNodeWithText("Cinema One").assertIsDisplayed()
    }

    @Test
    fun aHiddenGroupDoesNotHideTheSameNameInAnotherSource() {
        val hidden = listOf(
            LiveTvChannel("fixture", "demo", "fixture", "https://demo.test/fixture", "News"),
        ).liveTvBrowseGroups().single().id
        showScreen(extraSource = true, hiddenIds = setOf(hidden))
        compose.onNodeWithText("News One").assertDoesNotExist()
        compose.onNodeWithText("Backup News").assertIsDisplayed()
        compose.onNodeWithText("News").performClick()
        compose.onNodeWithText("Cinema One").assertDoesNotExist()
        compose.onNodeWithText("Backup Cinema").assertDoesNotExist()
        compose.onNodeWithText("Backup News").performClick()
        compose.runOnIdle { assertEquals(listOf(launches.single().channelId), launches.single().channelIds) }
    }

    @Test
    fun bulkVisibilityChangesOnlyTheSearchedGroups() {
        showScreen()
        compose.onNodeWithContentDescription("Manage groups").performClick()
        val fields = compose.onAllNodes(hasSetTextAction())
        val search = fields[fields.fetchSemanticsNodes().lastIndex]
        search.performTextInput("Films")
        compose.onNodeWithText("Hide all").performClick()
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithText("News One").assertIsDisplayed()
        compose.onNodeWithText("Cinema One").assertDoesNotExist()
        compose.onNodeWithContentDescription("Manage groups").performClick()
        compose.onNodeWithText("Select all").performClick()
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithText("Cinema One").assertIsDisplayed()
    }

    @Test
    fun groupSearchFindsAnOffscreenGroupInALargeProviderList() {
        showScreen(extraGroups = 900)
        compose.onAllNodes(hasSetTextAction())[0].performTextInput("Extra 899")
        compose.onNode(hasText("Extra 899") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected))
            .assertIsDisplayed().performClick()
        compose.onNodeWithText("Extra channel 899").assertIsDisplayed().performClick()
        compose.onNodeWithText("News One").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf(launches.single().channelId), launches.single().channelIds) }
    }

    private fun showScreen(extraSource: Boolean = false, hiddenIds: Set<String> = emptySet(), extraGroups: Int = 0) {
        val now = System.currentTimeMillis()
        val formatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss Z").withZone(ZoneOffset.UTC)
        fun time(offset: Long) = formatter.format(Instant.ofEpochMilli(now + offset))
        val playlist = """
            #EXTM3U url-tvg="https://demo.test/guide.xml"
            #EXTINF:-1 tvg-id="news" group-title="News",News One
            https://demo.test/news.m3u8
            #EXTINF:-1 tvg-id="cinema" group-title="Films",Cinema One
            https://demo.test/cinema.m3u8
        """.trimIndent() + buildString {
            repeat(extraGroups) { index ->
                append("\n#EXTINF:-1 group-title=\"Extra $index\",Extra channel $index\nhttps://demo.test/extra/$index.m3u8")
            }
        }
        val guide = """
            <tv>
              <programme channel="news" start="${time(-600_000)}" stop="${time(1_200_000)}"><title>World Report</title><desc>News from around the world.</desc></programme>
              <programme channel="news" start="${time(1_200_000)}" stop="${time(3_000_000)}"><title>Next Bulletin</title></programme>
              <programme channel="cinema" start="${time(-300_000)}" stop="${time(6_900_000)}"><title>Evening Cinema</title></programme>
            </tv>
        """.trimIndent()
        val store = object : LiveTvSourceStore {
            private var sources = if (extraSource) listOf(
                source, source.copy(id = "backup", name = "Backup playlist", location = "https://demo.test/backup.m3u"),
            ) else listOf(source)
            override fun load() = sources
            override fun save(sources: List<LiveTvSource>) { this.sources = sources }
        }
        val loader = object : LiveTvDocumentLoader {
            override suspend fun load(location: String, maxBytes: Int, allowLocalFiles: Boolean): LiveTvDocument =
                LiveTvDocument(
                    (when {
                        location.endsWith("guide.xml") -> guide
                        location.endsWith("backup.m3u") -> playlist.replace("News One", "Backup News").replace("Cinema One", "Backup Cinema")
                        else -> playlist
                    }).encodeToByteArray(), location,
                )
        }
        val repository = LiveTvRepository(store, loader)
        runBlocking { repository.load() }
        val groupStorage = object : LiveTvGroupVisibilityStorage {
            var hidden = hiddenIds
            override fun load(profileId: Int) = hidden
            override fun save(profileId: Int, hiddenGroupIds: Set<String>) { hidden = hiddenGroupIds }
        }
        val preferences = LiveTvGroupPreferences(1, groupStorage)
        compose.setContent {
            NuvioTheme {
                Box(Modifier.size(1100.dp, 780.dp)) {
                    LiveTvScreenContent(1, repository, launches::add, {}, Modifier, preferences)
                }
            }
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Cinema One").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun savePreview(name: String) {
        val directory = File("build/reports/livetv").apply { mkdirs() }
        val roots = compose.onAllNodes(isRoot())
        val root = roots[roots.fetchSemanticsNodes().lastIndex]
        ImageIO.write(root.captureToImage().toAwtImage(), "png", File(directory, "$name.png"))
    }
}
