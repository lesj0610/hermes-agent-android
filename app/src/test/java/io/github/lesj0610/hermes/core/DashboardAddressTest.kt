package io.github.lesj0610.hermes.core

import org.junit.Assert.assertEquals
import org.junit.Test

class DashboardAddressTest {

    @Test
    fun `suggests the gateway host with the dashboard port`() {
        assertEquals("192.0.2.10", defaultDashboardHost("http://192.0.2.10:8642"))
    }

    @Test
    fun `keeps a non-default scheme in the suggestion`() {
        assertEquals("https://hermes.example.com", defaultDashboardHost("https://hermes.example.com:8642"))
    }

    @Test
    fun `works when the gateway url carries no port`() {
        assertEquals("hermes.local", defaultDashboardHost("http://hermes.local"))
    }

    @Test
    fun `drops a gateway path but keeps the scheme`() {
        // A reverse-proxy prefix on the gateway says nothing about the
        // dashboard's, so carrying it over would guess wrong. The scheme is a
        // different matter: https on one is a strong hint for the other.
        assertEquals("https://example.com", defaultDashboardHost("https://example.com:8642/gateway"))
    }

    @Test
    fun `suggests nothing when there is no gateway address yet`() {
        assertEquals("", defaultDashboardHost(""))
    }
}
