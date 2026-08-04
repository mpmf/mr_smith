package com.mrsmith.chat;

import java.util.Random;
import java.util.UUID;

final class UuidV7 {

    private static final Random RANDOM = new Random();
    private static final int VERSION_BITS = 0x7000;               // version 7 (0111) at bits 48-51
    private static final long VARIANT_MASK = 0x3FFFFFFFFFFFFFFFL; // clears variant bits (63-62)
    private static final long VARIANT_BITS = 0x8000000000000000L; // RFC 4122 variant (10)
    private static final long RAND_A_MASK = 0xFFFL;               // rand_a (12 random bits) at bits 52-63
    private static final int TIMESTAMP_SHIFT = 16;

    private UuidV7() {
    }

    static UUID random() {
        long ms = System.currentTimeMillis();
        long top = (ms << TIMESTAMP_SHIFT) | VERSION_BITS | (RANDOM.nextLong() & RAND_A_MASK);
        long bottom = (RANDOM.nextLong() & VARIANT_MASK) | VARIANT_BITS;
        return new UUID(top, bottom);
    }
}
