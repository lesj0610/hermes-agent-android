package io.github.lesj0610.hermes.core

import java.net.URI

/**
 * Suggests a dashboard host from the gateway one.
 *
 * The two normally sit on the same machine — they share a HERMES_HOME — so
 * retyping the host is busywork. Only the host is suggested; the port has its
 * own field and defaults to [DASHBOARD_DEFAULT_PORT].
 *
 * It stays a suggestion, prefilled and never saved on the user's behalf,
 * because the addresses genuinely can differ: a reverse proxy can expose them
 * under separate names, and a common setup keeps the dashboard on loopback
 * behind an SSH tunnel while the gateway listens on the LAN.
 *
 * Any path on the gateway URL is dropped — a proxy prefix for one service says
 * nothing about the other's.
 */
fun defaultDashboardHost(gatewayUrl: String): String {
    val trimmed = gatewayUrl.trim()
    if (trimmed.isEmpty()) return ""
    val withScheme = if (trimmed.contains("://")) trimmed else "http://$trimmed"
    return runCatching {
        val uri = URI(withScheme)
        val host = uri.host?.takeIf { it.isNotBlank() } ?: return ""
        val scheme = uri.scheme ?: "http"
        if (scheme == "http") host else "$scheme://$host"
    }.getOrDefault("")
}
