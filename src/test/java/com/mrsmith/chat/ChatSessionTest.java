package com.mrsmith.chat;

import com.mrsmith.io.IO;
import com.mrsmith.provider.ChatMessage;
import com.mrsmith.provider.Provider;
import com.mrsmith.provider.ProviderException;
import com.mrsmith.provider.Role;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatSessionTest {

    @Test
    void sendsUserMessageAndStoresReplyInHistory() throws Exception {
        FakeProvider provider = new FakeProvider();
        StubIo io = new StubIo(List.of("hello", "/exit"));
        ChatSession session = new ChatSession(provider, io);
        session.run();
        assertEquals(1, provider.receivedHistories.get(0).size());
        assertEquals(Role.USER, provider.receivedHistories.get(0).get(0).role());
        assertEquals("hello", provider.receivedHistories.get(0).get(0).content());
        assertTrue(io.lines.stream().anyMatch(l -> l.contains("hello response")));
    }

    @Test
    void keepsContextAcrossTurns() throws Exception {
        FakeProvider provider = new FakeProvider();
        StubIo io = new StubIo(List.of("first", "second", "/exit"));
        ChatSession session = new ChatSession(provider, io);
        session.run();
        assertEquals(2, provider.receivedHistories.size());
        List<ChatMessage> secondTurn = provider.receivedHistories.get(1);
        assertEquals(3, secondTurn.size());
        assertEquals("first", secondTurn.get(0).content());
        assertEquals("first response", secondTurn.get(1).content());
        assertEquals("second", secondTurn.get(2).content());
    }

    @Test
    void resetClearsHistory() throws Exception {
        FakeProvider provider = new FakeProvider();
        StubIo io = new StubIo(List.of("first", "/reset", "second", "/exit"));
        ChatSession session = new ChatSession(provider, io);
        session.run();
        List<ChatMessage> secondTurn = provider.receivedHistories.get(1);
        assertEquals(1, secondTurn.size());
        assertEquals("second", secondTurn.get(0).content());
    }

    @Test
    void unknownCommandIsNotSentToProvider() throws Exception {
        FakeProvider provider = new FakeProvider();
        StubIo io = new StubIo(List.of("/bogus", "/exit"));
        ChatSession session = new ChatSession(provider, io);
        session.run();
        assertTrue(provider.receivedHistories.isEmpty());
        assertTrue(io.lines.stream().anyMatch(l -> l.contains("Unknown command")));
    }

    @Test
    void providerErrorIsShownAndLoopContinues() throws Exception {
        Provider failing = (history, sink) -> {
            throw new ProviderException("HTTP 401: bad key");
        };
        FakeProvider ok = new FakeProvider();
        StubIo io = new StubIo(List.of("hello", "again", "/exit"));
        ChatSession session = new ChatSession(new FirstThenProvider(failing, ok), io);
        session.run();
        assertTrue(io.lines.stream().anyMatch(l -> l.contains("HTTP 401")));
        assertEquals(1, ok.calls);
    }

    @Test
    void partialContentFromInterruptedStreamIsKeptInHistory() throws Exception {
        Provider interrupted = (history, sink) -> {
            sink.accept("partial");
            throw new ProviderException("Stream interrupted", null, "partial");
        };
        FakeProvider ok = new FakeProvider();
        StubIo io = new StubIo(List.of("hello", "again", "/exit"));
        ChatSession session = new ChatSession(new FirstThenProvider(interrupted, ok), io);
        session.run();
        List<ChatMessage> secondTurn = ok.receivedHistories.get(0);
        assertEquals(3, secondTurn.size());
        assertEquals(Role.ASSISTANT, secondTurn.get(1).role());
        assertEquals("partial", secondTurn.get(1).content());
    }

    @Test
    void genericProviderFailureIsShownAndLoopContinues() throws Exception {
        Provider failing = (history, sink) -> {
            throw new IllegalStateException("boom");
        };
        FakeProvider ok = new FakeProvider();
        StubIo io = new StubIo(List.of("hello", "again", "/exit"));
        ChatSession session = new ChatSession(new FirstThenProvider(failing, ok), io);
        session.run();
        assertTrue(io.lines.stream().anyMatch(l -> l.contains("boom")));
        assertEquals(1, ok.calls);
    }

    static class StubIo implements IO {
        final Deque<String> inputs;
        final List<String> lines = new ArrayList<>();

        StubIo(List<String> inputs) {
            this.inputs = new ArrayDeque<>(inputs);
        }

        @Override
        public String readLine() throws IOException {
            return inputs.poll();
        }

        @Override
        public void write(String text) {
            lines.add(text);
        }

        @Override
        public void writeLine(String line) {
            lines.add(line);
        }
    }

    static class FakeProvider implements Provider {
        final List<List<ChatMessage>> receivedHistories = new ArrayList<>();
        int calls = 0;

        @Override
        public ChatMessage send(List<ChatMessage> history, Consumer<String> tokenSink) {
            receivedHistories.add(new ArrayList<>(history));
            calls++;
            ChatMessage last = history.get(history.size() - 1);
            String reply = last.content() + " response";
            tokenSink.accept(reply);
            return new ChatMessage(Role.ASSISTANT, reply);
        }
    }

    static class FirstThenProvider implements Provider {
        final Provider first;
        final Provider then;
        int calls = 0;

        FirstThenProvider(Provider first, Provider then) {
            this.first = first;
            this.then = then;
        }

        @Override
        public ChatMessage send(List<ChatMessage> history, Consumer<String> tokenSink) {
            if (calls++ == 0) {
                return first.send(history, tokenSink);
            }
            return then.send(history, tokenSink);
        }
    }
}
