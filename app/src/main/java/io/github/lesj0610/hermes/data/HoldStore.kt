package io.github.lesj0610.hermes.data

/**
 * Where held conversations outlive the app's process: stored session id → the
 * live session it is held on. A hold dropped with the process would reopen
 * the conversation with nothing proven.
 */
interface HoldStore {
    fun load(): Map<String, String>

    fun save(holds: Map<String, String>)

    class InMemory(initial: Map<String, String> = emptyMap()) : HoldStore {
        @Volatile
        private var holds = initial

        override fun load(): Map<String, String> = holds

        override fun save(holds: Map<String, String>) {
            this.holds = holds
        }
    }
}
