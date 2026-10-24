package com.nuvio.app.features.livetv

import com.nuvio.app.core.storage.DesktopStorage
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LiveTvGroupPreferencesTest {
    private fun withStore(block: (DesktopStorage.Store, Path) -> Unit) {
        val directory = Files.createTempDirectory("livetv-group-preferences-test")
        val file = directory.resolve("livetv_group_visibility.properties")
        try {
            block(DesktopStorage.Store(file), file)
        } finally {
            Files.deleteIfExists(file)
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun hiddenGroupsSurviveRestart() = withStore { store, file ->
        LiveTvGroupPreferences(1, DesktopLiveTvGroupVisibilityStorage(store))
            .setHiddenGroupIds(setOf("a", "b"))

        val reloaded = LiveTvGroupPreferences(1, DesktopLiveTvGroupVisibilityStorage(DesktopStorage.Store(file)))

        assertEquals(setOf("a", "b"), reloaded.hiddenGroupIds.value)
    }

    @Test
    fun groupsAreIsolatedPerProfile() = withStore { store, _ ->
        LiveTvGroupPreferences(1, DesktopLiveTvGroupVisibilityStorage(store))
            .setHiddenGroupIds(setOf("profile-1-group"))
        LiveTvGroupPreferences(2, DesktopLiveTvGroupVisibilityStorage(store))
            .setHiddenGroupIds(setOf("profile-2-group"))

        assertEquals(
            setOf("profile-1-group"),
            LiveTvGroupPreferences(1, DesktopLiveTvGroupVisibilityStorage(store)).hiddenGroupIds.value,
        )
        assertEquals(
            setOf("profile-2-group"),
            LiveTvGroupPreferences(2, DesktopLiveTvGroupVisibilityStorage(store)).hiddenGroupIds.value,
        )
    }

    @Test
    fun unknownProfileDefaultsToNothingHidden() = withStore { store, _ ->
        val preferences = LiveTvGroupPreferences(7, DesktopLiveTvGroupVisibilityStorage(store))

        assertEquals(emptySet(), preferences.hiddenGroupIds.value)
    }

    @Test
    fun badPayloadDefaultsToNothingHidden() = withStore { store, _ ->
        store.putString("profile-3", "{not-json")

        val preferences = LiveTvGroupPreferences(3, DesktopLiveTvGroupVisibilityStorage(store))

        assertEquals(emptySet(), preferences.hiddenGroupIds.value)
    }

    @Test
    fun storageResetClearsHiddenGroups() = withStore { store, file ->
        LiveTvGroupPreferences(1, DesktopLiveTvGroupVisibilityStorage(store))
            .setHiddenGroupIds(setOf("a"))

        Files.deleteIfExists(file)

        val afterReset = LiveTvGroupPreferences(
            1,
            DesktopLiveTvGroupVisibilityStorage(DesktopStorage.Store(file)),
        )

        assertEquals(emptySet(), afterReset.hiddenGroupIds.value)
    }

    @Test
    fun failedWriteDoesNotPublish() {
        val failing = object : LiveTvGroupVisibilityStorage {
            override fun load(profileId: Int): Set<String> = setOf("persisted")

            override fun save(profileId: Int, hiddenGroupIds: Set<String>) {
                throw IllegalStateException("disk full")
            }
        }
        val preferences = LiveTvGroupPreferences(1, failing)

        assertFailsWith<IllegalStateException> {
            preferences.setHiddenGroupIds(setOf("new"))
        }

        assertEquals(setOf("persisted"), preferences.hiddenGroupIds.value)
    }

    @Test
    fun inputMutationDoesNotAffectStoredSnapshot() = withStore { store, _ ->
        val mutable = mutableSetOf("a")
        LiveTvGroupPreferences(1, DesktopLiveTvGroupVisibilityStorage(store))
            .setHiddenGroupIds(mutable)

        mutable += "b"

        assertEquals(
            setOf("a"),
            LiveTvGroupPreferences(1, DesktopLiveTvGroupVisibilityStorage(store)).hiddenGroupIds.value,
        )
    }
}
