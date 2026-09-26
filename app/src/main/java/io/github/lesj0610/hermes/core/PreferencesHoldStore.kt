package io.github.lesj0610.hermes.core

import android.content.Context
import androidx.core.content.edit
import io.github.lesj0610.hermes.data.HoldStore
import io.github.lesj0610.hermes.data.StoredHold
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * [HoldStore] in the app's private preferences: a handful of entries, read
 * once at start.
 *
 * Kept as one JSON list under [HOLDS]. The first version kept each hold as its
 * own entry, stored session id → live session id, with no gateway; those are
 * read as holds on an unknown gateway and carried into the list on the next
 * write, never dropped.
 */
class PreferencesHoldStore(context: Context) : HoldStore {
    private val prefs = context.getSharedPreferences("held_conversations", Context.MODE_PRIVATE)

    override fun load(): List<StoredHold> {
        val all = prefs.all
        val current = (all[HOLDS] as? String)
            ?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }
            .orEmpty()
        val earlier = all.filterKeys { it != HOLDS }
            .mapNotNull { (sessionId, liveId) -> (liveId as? String)?.let { StoredHold(null, sessionId, it) } }
            .filter { old -> current.none { it.sessionId == old.sessionId && it.liveId == old.liveId } }
        return current + earlier
    }

    // Committed, not applied: a hold written only in memory is lost with the
    // process, and the conversation would reopen with nothing proven. Never
    // called on the main thread.
    override fun save(holds: List<StoredHold>) {
        prefs.edit(commit = true) {
            clear()
            putString(HOLDS, json.encodeToString(serializer, holds))
        }
    }

    private companion object {
        const val HOLDS = "holds"
        val json = Json { ignoreUnknownKeys = true }
        val serializer = ListSerializer(StoredHold.serializer())
    }
}
