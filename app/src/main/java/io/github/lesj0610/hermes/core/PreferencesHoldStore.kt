package io.github.lesj0610.hermes.core

import android.content.Context
import androidx.core.content.edit
import io.github.lesj0610.hermes.data.HoldStore

/** [HoldStore] in the app's private preferences: a handful of entries, read once at start. */
class PreferencesHoldStore(context: Context) : HoldStore {
    private val prefs = context.getSharedPreferences("held_conversations", Context.MODE_PRIVATE)

    override fun load(): Map<String, String> =
        prefs.all.mapNotNull { (key, value) -> (value as? String)?.let { key to it } }.toMap()

    // Committed, not applied: a hold written only in memory is lost with the
    // process, and the conversation would reopen with nothing proven. Never
    // called on the main thread.
    override fun save(holds: Map<String, String>) {
        prefs.edit(commit = true) {
            clear()
            holds.forEach { (key, liveId) -> putString(key, liveId) }
        }
    }
}
