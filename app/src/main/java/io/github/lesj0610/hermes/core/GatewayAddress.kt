package io.github.lesj0610.hermes.core

import java.net.URI

/** The gateway api_server's default port. */
const val GATEWAY_DEFAULT_PORT = 8642

/** The dashboard server's default port. */
const val DASHBOARD_DEFAULT_PORT = 9119

/**
 * A server address split the way the settings screen asks for it: a host field
 * and a port field, rather than one box where the port hides behind a colon.
 *
 * [host] may carry a scheme and a path (`https://example.com/gateway`); it just
 * never carries the port, which lives in [port].
 */
data class Endpoint(val host: String, val port: Int)

/**
 * Splits a stored URL back into the two fields, for prefilling the form.
 *
 * A URL with no port yields [defaultPort] rather than the scheme default,
 * because nobody runs Hermes on 80 or 443 and showing those would invite the
 * user to save an address that cannot work.
 */
fun parseEndpoint(url: String, defaultPort: Int): Endpoint {
    val trimmed = url.trim().trimEnd('/')
    if (trimmed.isEmpty()) return Endpoint("", defaultPort)

    val withScheme = if (trimmed.contains("://")) trimmed else "http://$trimmed"
    return runCatching {
        val uri = URI(withScheme)
        val hostName = uri.host ?: return Endpoint(trimmed, defaultPort)
        val scheme = uri.scheme ?: "http"
        val path = uri.rawPath.orEmpty().trimEnd('/')
        // Only show the scheme when it is not the assumed default, so the
        // common case stays a bare host and the field looks like what the user
        // typed rather than something the app rewrote behind their back.
        val hostPart = buildString {
            if (scheme != "http") append("$scheme://")
            append(hostName)
            append(path)
        }
        Endpoint(hostPart, if (uri.port != -1) uri.port else defaultPort)
    }.getOrDefault(Endpoint(trimmed, defaultPort))
}

/**
 * Builds the URL the HTTP client uses from the two fields.
 *
 * Any port the user left inside the host field wins over [port] — pasting
 * `192.0.2.10:9000` should not silently connect somewhere else.
 */
fun buildEndpointUrl(host: String, port: Int): String {
    val trimmed = host.trim().trimEnd('/')
    if (trimmed.isEmpty()) return ""

    val withScheme = if (trimmed.contains("://")) trimmed else "http://$trimmed"
    return runCatching {
        val uri = URI(withScheme)
        val hostName = uri.host ?: return trimmed
        val scheme = uri.scheme ?: "http"
        val effectivePort = if (uri.port != -1) uri.port else port
        val path = uri.rawPath.orEmpty().trimEnd('/')
        "$scheme://$hostName:$effectivePort$path"
    }.getOrDefault(trimmed)
}

/** Parses a port field, falling back to [fallback] on anything unusable. */
fun coercePort(input: String, fallback: Int): Int =
    input.trim().toIntOrNull()?.takeIf { it in 1..65535 } ?: fallback
