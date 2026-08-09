package io.github.lesj0610.hermes.net

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readLine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * One decoded SSE frame: the optional `event:` name plus the concatenated
 * `data:` payload.
 *
 * The /v1/runs stream never sets [name], but /v1/chat/completions does
 * (`event: hermes.tool.progress`), so the parser keeps the field rather than
 * hard-coding one route's habits.
 */
data class SseFrame(val name: String?, val data: String)

/**
 * Minimal SSE reader.
 *
 * Deliberately hand-rolled instead of using Ktor's SSE plugin: the gateway
 * signals event type inside the JSON body on the route the app actually uses,
 * and comment frames (`: keepalive`) arrive on a timer. Both are trivial here
 * and awkward through a plugin that assumes `event:` lines are authoritative.
 *
 * Frame rules implemented, per the SSE spec subset the server emits:
 *  - a blank line dispatches the accumulated frame
 *  - lines starting with `:` are comments and are skipped
 *  - `data:` values concatenate with newlines across repeated lines
 *  - a frame with empty data is not dispatched
 */
fun ByteReadChannel.sseFrames(): Flow<SseFrame> = flow {
    val data = StringBuilder()
    var name: String? = null

    while (true) {
        val line = readLine() ?: break

        if (line.isEmpty()) {
            if (data.isNotEmpty()) {
                emit(SseFrame(name, data.toString()))
                data.setLength(0)
                name = null
            }
            continue
        }
        if (line.startsWith(":")) continue

        val colon = line.indexOf(':')
        val field = if (colon < 0) line else line.substring(0, colon)
        var value = if (colon < 0) "" else line.substring(colon + 1)
        if (value.startsWith(" ")) value = value.substring(1)

        when (field) {
            "event" -> name = value
            "data" -> {
                if (data.isNotEmpty()) data.append('\n')
                data.append(value)
            }
            // id / retry carry no meaning for this client
            else -> Unit
        }
    }

    // A stream cut mid-frame still delivers what arrived.
    if (data.isNotEmpty()) emit(SseFrame(name, data.toString()))
}
