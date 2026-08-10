package com.scivicslab.gpubroker.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("CidrRange — expand a broker.nodes entry into individual addresses")
class CidrRangeTest {

    @Test
    void plainAddress_expandsToItself() {
        assertEquals(List.of("192.168.5.16"), CidrRange.expand("192.168.5.16"));
    }

    @Test
    void slash26_expandsTo64Addresses_startingAtTheNetworkAddress() {
        List<String> addresses = CidrRange.expand("192.168.5.0/26");

        assertEquals(64, addresses.size());
        assertEquals("192.168.5.0", addresses.get(0));
        assertEquals("192.168.5.63", addresses.get(63));
    }

    @Test
    void slash26_baseNotOnABoundary_stillRoundsDownToTheNetworkAddress() {
        // .16 is inside the 192.168.5.0/26 block, not the network address itself.
        List<String> addresses = CidrRange.expand("192.168.5.16/26");

        assertEquals(64, addresses.size());
        assertEquals("192.168.5.0", addresses.get(0));
        assertEquals("192.168.5.63", addresses.get(63));
    }

    @Test
    void slash24_expandsTo256Addresses() {
        assertEquals(256, CidrRange.expand("192.168.5.0/24").size());
    }

    @Test
    void largerThanSlash24_isRejected() {
        assertThrows(IllegalArgumentException.class, () -> CidrRange.expand("192.168.0.0/16"));
    }

    @Test
    void invalidPrefixLength_isRejected() {
        assertThrows(IllegalArgumentException.class, () -> CidrRange.expand("192.168.5.0/33"));
        assertThrows(IllegalArgumentException.class, () -> CidrRange.expand("192.168.5.0/not-a-number"));
    }

    @Test
    void slash32_expandsToASingleAddress() {
        List<String> addresses = CidrRange.expand("192.168.5.16/32");

        assertEquals(1, addresses.size());
        assertTrue(addresses.contains("192.168.5.16"));
    }
}
