package com.mrsmith.chat;

import java.util.Random;
import java.util.UUID;

final class UuidV7 {

    private static final Random RANDOM = new Random();

    private UuidV7() {
    }

    static UUID random() {
        long ms = System.currentTimeMillis();
        long top = (ms << 16) | 0x7000;
        long bottom = (RANDOM.nextLong() & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
        return new UUID(top, bottom);
    }
}
