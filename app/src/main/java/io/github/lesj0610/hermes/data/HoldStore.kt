package io.github.lesj0610.hermes.data

import kotlinx.serialization.Serializable

/**
 * One held conversation as recorded: on which gateway, which stored session,
 * held on which live session.
 */
@Serializable
data class StoredHold(
    /**
     * The gateway's identity (see `gatewayIdentity`); null for a hold an
     * earlier version recorded without one — which gateway it was on is then
     * not known.
     */
    val gateway: String? = null,
    val sessionId: String,
    val liveId: String,
)

/**
 * Where held conversations outlive the app's process. A hold dropped with the
 * process would reopen the conversation with nothing proven.
 */
interface HoldStore {
    fun load(): List<StoredHold>

    fun save(holds: List<StoredHold>)

    class InMemory(initial: List<StoredHold> = emptyList()) : HoldStore {
        @Volatile
        private var holds = initial

        override fun load(): List<StoredHold> = holds

        override fun save(holds: List<StoredHold>) {
            this.holds = holds
        }
    }
}
