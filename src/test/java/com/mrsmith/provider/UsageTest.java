package com.mrsmith.provider;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UsageTest {

    @Test
    void totalsSumNonNullFields() {
        assertEquals(1500, new Usage(1200, 300).total());
    }

    @Test
    void totalsHandleNullFields() {
        assertEquals(1200, new Usage(1200, null).total());
        assertEquals(300, new Usage(null, 300).total());
        assertEquals(0, new Usage(null, null).total());
    }
}
