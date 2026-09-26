package io.github.lesj0610.hermes.core

import android.content.Context
import io.github.lesj0610.hermes.data.StoredHold
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PreferencesHoldStoreTest {

    private val context: Context = RuntimeEnvironment.getApplication()

    @Test
    fun `holds the first version wrote are read without a gateway and kept on the next write`() {
        // The first version: one entry per hold, stored session id to live session id.
        context.getSharedPreferences("held_conversations", Context.MODE_PRIVATE)
            .edit().putString("s1", "live-1").commit()
        val store = PreferencesHoldStore(context)
        assertEquals(listOf(StoredHold(null, "s1", "live-1")), store.load())

        store.save(store.load() + StoredHold("http://gw-a.invalid", "s2", "live-2"))
        assertEquals(
            listOf(StoredHold(null, "s1", "live-1"), StoredHold("http://gw-a.invalid", "s2", "live-2")),
            PreferencesHoldStore(context).load(),
        )
    }

    @Test
    fun `a released hold is gone from the record`() {
        val store = PreferencesHoldStore(context)
        store.save(listOf(StoredHold("http://gw-a.invalid", "s1", "live-1")))
        store.save(emptyList())
        assertTrue(PreferencesHoldStore(context).load().isEmpty())
    }
}
