package com.mrsmith.provider;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ChatMessageTest {

    @Test
    void twoArgConstructorDefaultsThinkingToNull() {
        ChatMessage message = new ChatMessage(Role.USER, "hello");
        assertEquals("hello", message.content());
        assertNull(message.thinking());
    }

    @Test
    void threeArgConstructorStoresThinking() {
        ChatMessage message = new ChatMessage(Role.ASSISTANT, "answer", "think step by step");
        assertEquals("answer", message.content());
        assertEquals("think step by step", message.thinking());
    }
}
