package com.mrsmith.provider;

import org.junit.jupiter.api.Test;

import java.util.List;

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

    @Test
    void carriesToolCallsAndToolCallId() {
        ChatMessage assistant = new ChatMessage(Role.ASSISTANT, null, null, List.of(), null);
        assertEquals(Role.ASSISTANT, assistant.role());
        ChatMessage toolResult = new ChatMessage(Role.TOOL, "42", null, null, "call_1");
        assertEquals("call_1", toolResult.toolCallId());
    }
}
