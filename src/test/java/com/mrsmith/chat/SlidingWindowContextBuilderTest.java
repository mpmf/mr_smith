package com.mrsmith.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mrsmith.provider.ChatMessage;
import com.mrsmith.provider.Role;
import com.mrsmith.provider.ToolCall;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SlidingWindowContextBuilderTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void startSeedsSystemPrompt() {
        SlidingWindowContextBuilder builder = new SlidingWindowContextBuilder();
        builder.start("You are helpful", 1000);
        List<ChatMessage> context = builder.messages();
        assertEquals(1, context.size());
        assertEquals(Role.SYSTEM, context.get(0).role());
        assertEquals("You are helpful", context.get(0).content());
    }

    @Test
    void underBudgetAccumulatesEverything() {
        SlidingWindowContextBuilder builder = new SlidingWindowContextBuilder();
        builder.start(null, 1000);
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
    void dropsOldestTurnWhenOverBudget() {
        SlidingWindowContextBuilder builder = new SlidingWindowContextBuilder();
        builder.start(null, 5);
        builder.appendUser("1111");       // 1 token
        builder.appendAssistant("22222222"); // 2 tokens (turn 1 = 3 tokens)
        builder.appendUser("33333333");   // 2 tokens (total 5)
        builder.appendAssistant("4444");  // 1 token -> total 6 > 5, drop turn 1
        List<ChatMessage> context = builder.messages();
        assertEquals(2, context.size());
        assertEquals("33333333", context.get(0).content());
        assertEquals("4444", context.get(1).content());
    }

    @Test
    void keepsCurrentTurnEvenWhenAloneOverBudget() {
        SlidingWindowContextBuilder builder = new SlidingWindowContextBuilder();
        builder.start(null, 1);
        builder.appendUser("aaaaaaaaaaaaaaaa"); // 4 tokens > budget, single turn
        assertEquals(1, builder.messages().size());
    }

    @Test
    void systemMessagesArePinned() {
        SlidingWindowContextBuilder builder = new SlidingWindowContextBuilder();
        builder.start("sys", 2);          // "sys" = 1 token
        builder.appendUser("aaaa");        // 1 token
        builder.appendAssistant("aaaaaaaa"); // 2 tokens -> over budget, but single turn
        List<ChatMessage> context = builder.messages();
        assertEquals(3, context.size());
        assertEquals(Role.SYSTEM, context.get(0).role());
        assertEquals("sys", context.get(0).content());
    }

    @Test
    void dropsWholeTurnIncludingToolCallAndResult() throws Exception {
        SlidingWindowContextBuilder builder = new SlidingWindowContextBuilder();
        builder.start(null, 5);
        builder.appendUser("u1");          // 1 token
        builder.appendAssistantToolCalls(List.of(new ToolCall("c1", "t", JSON.readTree("{}")))); // 3 tokens
        builder.appendToolResult("c1", "r"); // 2 tokens -> total 6, single turn, no trim
        builder.appendUser("u2");          // 1 token -> 2 turns, trim turn 1
        List<ChatMessage> context = builder.messages();
        assertEquals(1, context.size());
        assertEquals(Role.USER, context.get(0).role());
        assertEquals("u2", context.get(0).content());
    }

    @Test
    void startResetsTheWindow() {
        SlidingWindowContextBuilder builder = new SlidingWindowContextBuilder();
        builder.start(null, 1000);
        builder.appendUser("one");
        builder.start("sys", 1000);
        List<ChatMessage> context = builder.messages();
        assertEquals(1, context.size());
        assertEquals("sys", context.get(0).content());
    }

    @Test
    void zeroBudgetFallsBackToDefaultBudget() {
        SlidingWindowContextBuilder builder = new SlidingWindowContextBuilder();
        builder.start(null, 0);
        builder.appendUser("hello");
        builder.appendAssistant("hi");
        assertEquals(2, builder.messages().size());
    }

    @Test
    void messagesIsImmutable() {
        SlidingWindowContextBuilder builder = new SlidingWindowContextBuilder();
        builder.start(null, 1000);
        builder.appendUser("hello");
        List<ChatMessage> context = builder.messages();
        assertThrows(UnsupportedOperationException.class,
                () -> context.add(new ChatMessage(Role.USER, "x")));
    }
}
