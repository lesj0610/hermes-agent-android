package io.github.lesj0610.hermes.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The case that actually bit: typing a bare host produced a connection error
 * that looked like the server was down, when the address was simply missing a
 * scheme and a port. The settings screen now asks for host and port separately,
 * and these pin the round trip between the two fields and the stored URL.
 */
class GatewayAddressTest {

    @Test
    fun `a bare host builds a usable url with the given port`() {
        assertEquals("http://192.0.2.10:8642", buildEndpointUrl("192.0.2.10", 8642))
        assertEquals("http://hermes.local:9119", buildEndpointUrl("hermes.local", 9119))
    }

    @Test
    fun `https is preserved and does not fall back to 443`() {
        // Nobody runs Hermes on the scheme default, so the port field wins.
        assertEquals("https://example.com:8642", buildEndpointUrl("https://example.com", 8642))
    }

    @Test
    fun `a port pasted into the host field wins over the port field`() {
        // Otherwise pasting host:9000 would quietly connect to 8642 instead.
        assertEquals("http://192.0.2.10:9000", buildEndpointUrl("192.0.2.10:9000", 8642))
    }

    @Test
    fun `a reverse-proxy path survives`() {
        assertEquals(
            "https://example.com:8642/gateway",
            buildEndpointUrl("https://example.com/gateway", 8642),
        )
    }

    @Test
    fun `whitespace and trailing slashes are trimmed`() {
        assertEquals("http://192.0.2.10:8642", buildEndpointUrl("  192.0.2.10/  ", 8642))
    }

    @Test
    fun `empty host builds nothing`() {
        assertEquals("", buildEndpointUrl("   ", 8642))
    }

    @Test
    fun `parsing splits a stored url back into the two fields`() {
        assertEquals(Endpoint("192.0.2.10", 8642), parseEndpoint("http://192.0.2.10:8642", 8642))
        assertEquals(
            Endpoint("https://example.com", 9119),
            parseEndpoint("https://example.com:9119", 8642),
        )
    }

    @Test
    fun `parsing hides an http scheme but keeps anything else`() {
        // The field should look like what a person would type.
        assertEquals("192.0.2.10", parseEndpoint("http://192.0.2.10:8642", 8642).host)
        assertEquals("https://example.com", parseEndpoint("https://example.com:8642", 8642).host)
    }

    @Test
    fun `parsing a url without a port yields the default, not 80`() {
        assertEquals(Endpoint("192.0.2.10", 8642), parseEndpoint("http://192.0.2.10", 8642))
    }

    @Test
    fun `parsing an empty url yields an empty host and the default port`() {
        assertEquals(Endpoint("", 9119), parseEndpoint("", 9119))
    }

    @Test
    fun `port coercion rejects nonsense and out-of-range values`() {
        assertEquals(8642, coercePort("", 8642))
        assertEquals(8642, coercePort("abc", 8642))
        assertEquals(8642, coercePort("0", 8642))
        assertEquals(8642, coercePort("70000", 8642))
        assertEquals(9000, coercePort(" 9000 ", 8642))
    }
}
