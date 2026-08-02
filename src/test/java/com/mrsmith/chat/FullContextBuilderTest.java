package com.mrsmith.chat;

import com.mrsmith.provider.ChatMessage;
import com.mrsmith.provider.Role;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FullContextBuilderTest {

    private final FullContextBuilder builder = new FullContextBuilder();

    @Test
    void startSeedsSystemPrompt() {
        builder.start("You are helpful");
        List<ChatMessage> context = builder.messages();
        assertEquals(1, context.size());
        assertEquals(Role.SYSTEM, context.get(0).role());
        assertEquals("You are helpful", context.get(0).content());
    }

    @Test
    void startWithoutSystemPromptSeedsNothing() {
        builder.start(null);
        assertTrue(builder.messages().isEmpty());
    }

    @Test
    void appendsAccumulateInOrder() {
        builder.start(null);
        builder.appendUser("hello");
        builder.appendAssistant("hi");
        builder.appendUser("again");
        List<ChatMessage> context = builder.messages();
        assertEquals(3, context.size());
        assertEquals("hello", context.get(0).content());
        assertEquals("hi", context.get(1).content());
        assertEquals("again", context.get(2).content());
    }

    @Test
    void startResetsTheWindow() {
        builder.start(null);
        builder.appendUser("one");
        builder.start("sys");
        List<ChatMessage> context = builder.messages();
        assertEquals(1, context.size());
        assertEquals("sys", context.get(0).content());
    }

    @Test
    void messagesHasNoThinking() {
        builder.start(null);
        builder.appendAssistant("answer");
        ChatMessage message = builder.messages().get(0);
        assertEquals(Role.ASSISTANT, message.role());
        assertEquals("answer", message.content());
        assertTrue(message.thinking() == null);
    }

    @Test
    void messagesIsImmutable() {
        builder.start(null);
        builder.appendUser("hello");
        List<ChatMessage> context = builder.messages();
        assertThrows(UnsupportedOperationException.class, () -> context.add(new ChatMessage(Role.USER, "x")));
    }
}
