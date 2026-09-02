package com.solidus.analytics.cloud;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Audit B-7 regression: G6 IP masking must cover BOTH address families. The
 * pre-fix code only masked dotted-quad IPv4 and forwarded IPv6 addresses
 * verbatim to the relay (full player IPs leaving the server unmasked).
 */
@DisplayName("TelemetryCollector.maskIp (G6 / audit B-7)")
class TelemetryCollectorMaskIpTest {

    @Test
    @DisplayName("IPv4 keeps the first three octets")
    void ipv4Masked() {
        assertEquals("91.198.44.*", TelemetryCollector.maskIp("91.198.44.170"));
        assertEquals("127.0.0.*", TelemetryCollector.maskIp("127.0.0.1"));
        assertEquals("8.8.8.*", TelemetryCollector.maskIp("8.8.8.8"));
    }

    @Test
    @DisplayName("IPv6 zeroes the low 64 bits (interface identifier)")
    void ipv6Masked() {
        // Java getHostAddress form: full uncompressed 8 groups.
        assertEquals("2001:db8:1234:5678::*",
            TelemetryCollector.maskIp("2001:db8:1234:5678:9abc:def0:1234:5678"));
        assertEquals("fe80:0:0:0::*", TelemetryCollector.maskIp("fe80:0:0:0:1234:5678:9abc:def0"));
    }

    @Test
    @DisplayName("unknown address shapes are fully masked, never leaked verbatim")
    void unknownShapeFullyMasked() {
        assertEquals("masked:5", TelemetryCollector.maskIp("unix+"));
        assertEquals("", TelemetryCollector.maskIp(""));
        assertEquals(null, TelemetryCollector.maskIp(null));
    }
}
