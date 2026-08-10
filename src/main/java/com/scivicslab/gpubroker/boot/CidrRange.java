package com.scivicslab.gpubroker.boot;

import java.util.ArrayList;
import java.util.List;

/**
 * Expands one {@code broker.nodes} entry into individual IPv4 addresses to
 * probe. A plain address (no {@code /}) expands to itself; a CIDR block
 * (e.g. {@code 192.168.5.0/26}) expands to every address in that block,
 * network and broadcast addresses included — probing a few extra addresses
 * that answer nothing is harmless, so there is no need to special-case them.
 *
 * <p>Blocks larger than {@code /24} (more than 256 addresses) are rejected:
 * a typo like {@code /8} would otherwise silently turn one config line into
 * millions of probes.
 */
final class CidrRange {

    private static final int MIN_PREFIX_LENGTH = 24;

    private CidrRange() {
    }

    static List<String> expand(String entry) {
        int slash = entry.indexOf('/');
        if (slash < 0) {
            return List.of(entry);
        }

        String base = entry.substring(0, slash);
        int prefixLength = parsePrefixLength(entry, entry.substring(slash + 1));
        if (prefixLength < MIN_PREFIX_LENGTH) {
            throw new IllegalArgumentException(
                    "CIDR block too large (max " + (1 << (32 - MIN_PREFIX_LENGTH)) + " addresses, i.e. /" + MIN_PREFIX_LENGTH + " or narrower): " + entry);
        }

        long hostBits = 32 - prefixLength;
        long networkAddress = (toLong(base) >>> hostBits) << hostBits;
        long count = 1L << hostBits;

        List<String> addresses = new ArrayList<>();
        for (long i = 0; i < count; i++) {
            addresses.add(toDottedQuad(networkAddress + i));
        }
        return addresses;
    }

    private static int parsePrefixLength(String entry, String raw) {
        int prefixLength;
        try {
            prefixLength = Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid CIDR prefix length: " + entry, e);
        }
        if (prefixLength < 0 || prefixLength > 32) {
            throw new IllegalArgumentException("invalid CIDR prefix length: " + entry);
        }
        return prefixLength;
    }

    private static long toLong(String ipv4) {
        String[] parts = ipv4.split("\\.");
        if (parts.length != 4) {
            throw new IllegalArgumentException("not an IPv4 address: " + ipv4);
        }
        long result = 0;
        for (String part : parts) {
            result = (result << 8) | Integer.parseInt(part);
        }
        return result;
    }

    private static String toDottedQuad(long address) {
        return ((address >> 24) & 0xFF) + "." + ((address >> 16) & 0xFF) + "." + ((address >> 8) & 0xFF) + "." + (address & 0xFF);
    }
}
