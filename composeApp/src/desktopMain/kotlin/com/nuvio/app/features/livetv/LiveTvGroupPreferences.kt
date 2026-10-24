package com.nuvio.app.features.livetv

import com.nuvio.app.core.storage.DesktopStorage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal interface LiveTvGroupVisibilityStorage {
    fun load(profileId: Int): Set<String>

    fun save(profileId: Int, hiddenGroupIds: Set<String>)
}

/**
 * Profile-scoped hidden Live TV groups. Values are opaque group identifiers; display
 * names and credentials never reach this store.
 */
internal class DesktopLiveTvGroupVisibilityStorage(
    private val store: DesktopStorage.Store = DesktopStorage.store("livetv_group_visibility"),
) : LiveTvGroupVisibilityStorage {
    override fun load(profileId: Int): Set<String> =
        store.getStringSet(key(profileId)) ?: emptySet()

    override fun save(profileId: Int, hiddenGroupIds: Set<String>) {
        store.putStringSet(key(profileId), hiddenGroupIds)
    }

    private fun key(profileId: Int): String = "profile-$profileId"
}

/**
 * Hide/show preference for the Live TV groups of one profile.
 *
 * Invariants:
 * - The published value changes only after the storage write succeeded, so a failed
 *   write can never make the UI claim state that is not persisted.
 * - [setHiddenGroupIds] copies its input, so later caller mutation cannot leak into the
 *   store or the published value.
 * - Save and publish share one monitor, so concurrent updates are published in the same
 *   order they were written.
 * - Unreadable stored JSON degrades to "nothing hidden", which is reversible through the
 *   UI and never discards provider data.
 */
internal class LiveTvGroupPreferences(
    private val profileId: Int,
    private val storage: LiveTvGroupVisibilityStorage = DesktopLiveTvGroupVisibilityStorage(),
) {
    private val lock = Any()
    private val _hiddenGroupIds = MutableStateFlow(storage.load(profileId))
    val hiddenGroupIds: StateFlow<Set<String>> = _hiddenGroupIds.asStateFlow()

    fun setHiddenGroupIds(ids: Set<String>) {
        val snapshot = ids.toSet()
        synchronized(lock) {
            storage.save(profileId, snapshot)
            _hiddenGroupIds.value = snapshot
        }
    }
}
