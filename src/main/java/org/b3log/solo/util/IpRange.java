/* SPDX-License-Identifier: AGPL-3.0-or-later */
package org.b3log.solo.util;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;

/** A literal address or CIDR network; never resolves host names. */
public final class IpRange {
    private final byte[] network;
    private final int prefix;
    private final String text;

    private IpRange(final byte[] network, final int prefix, final String text) {
        this.network = network;
        this.prefix = prefix;
        this.text = text;
    }

    public static IpRange parse(final String input) {
        final String[] parts = (input == null ? "" : input.trim()).split("/", -1);
        if (parts.length > 2) throw new IllegalArgumentException("请输入 IP 地址或 CIDR 网段。");
        final byte[] address = IpAddresses.bytes(parts[0]);
        if (address == null) throw new IllegalArgumentException("IP 地址格式不正确。");
        final int bits = address.length * 8;
        final int prefix;
        try {
            if (parts.length == 2 && !parts[1].matches("[0-9]{1,3}")) throw new NumberFormatException();
            prefix = parts.length == 1 ? bits : Integer.parseInt(parts[1]);
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("CIDR 前缀长度不正确。");
        }
        if (prefix < 0 || prefix > bits) throw new IllegalArgumentException("CIDR 前缀超出地址范围。");
        final byte[] network = Arrays.copyOf(address, address.length);
        for (int bit = prefix; bit < bits; bit++) network[bit / 8] &= ~(1 << (7 - bit % 8));
        try {
            final String base = InetAddress.getByAddress(network).getHostAddress();
            return new IpRange(network, prefix, base + (prefix == bits ? "" : "/" + prefix));
        } catch (UnknownHostException impossible) {
            throw new IllegalArgumentException(impossible);
        }
    }

    public boolean contains(final String value) {
        return contains(IpAddresses.bytes(value));
    }

    public boolean contains(final byte[] address) {
        if (address == null || address.length != network.length) return false;
        for (int bit = 0; bit < prefix; bit++) {
            final int mask = 1 << (7 - bit % 8);
            if ((address[bit / 8] & mask) != (network[bit / 8] & mask)) return false;
        }
        return true;
    }

    public String baseAddress() { return text.split("/", 2)[0]; }
    @Override public String toString() { return text; }
}
