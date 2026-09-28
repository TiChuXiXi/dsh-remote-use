package com.dsh.remote.net;

import java.security.SecureRandom;

/** RFC 4122 version 4 UUIDs, SecureRandom-backed. */
public final class Uuid {

    private static final SecureRandom RNG = new SecureRandom();
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private Uuid() {
    }

    /** RFC 4122 version 4, lowercase, e.g. "3f0c4a9e-6d1b-4a2f-8c37-9d2e8f1a5b10". */
    public static String v4() {
        byte[] b = new byte[16];
        RNG.nextBytes(b);
        b[6] = (byte) ((b[6] & 0x0F) | 0x40); // version 4
        b[8] = (byte) ((b[8] & 0x3F) | 0x80); // variant 10xx
        StringBuilder sb = new StringBuilder(36);
        for (int i = 0; i < 16; i++) {
            if (i == 4 || i == 6 || i == 8 || i == 10) {
                sb.append('-');
            }
            sb.append(HEX[(b[i] >> 4) & 0x0F]);
            sb.append(HEX[b[i] & 0x0F]);
        }
        return sb.toString();
    }
}