package com.mrsmith.provider;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderExceptionTest {

    @Test
    void exposesOptionalPartialContent() {
        ProviderException withPartial = new ProviderException("Stream interrupted", null, "half a reply");
        assertTrue(withPartial.hasPartialContent());
        assertEquals("half a reply", withPartial.partialContent());

        ProviderException plain = new ProviderException("HTTP 401: bad key");
        assertFalse(plain.hasPartialContent());
    }

    @Test
    void exposesOptionalPartialThinking() {
        ProviderException e = new ProviderException("Stream interrupted", null, "partial", "think");
        assertTrue(e.hasPartialContent());
        assertEquals("partial", e.partialContent());
        assertEquals("think", e.partialThinking());
    }
}
