package com.mrsmith.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.mrsmith.config.AgentCatalog;
import com.mrsmith.config.AgentConfig;
import com.mrsmith.config.AppConfig;
import com.mrsmith.config.ProviderConfig;
import com.mrsmith.io.IO;
import com.mrsmith.provider.ChatMessage;
import com.mrsmith.provider.Provider;
import com.mrsmith.provider.ProviderException;
import com.mrsmith.provider.ProviderFactory;
import com.mrsmith.provider.ProviderResponse;
import com.mrsmith.provider.Role;
import com.mrsmith.provider.Usage;
import com.mrsmith.session.TranscriptWriter;
import com.mrsmith.tool.Tool;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatSessionTest {

    @Test
    void sendsUserMessageAndStoresReplyInHistory() throws Exception {
        FakeProvider provider = new FakeProvider();
        FakeTranscriptWriter transcripts = new FakeTranscriptWriter();
        StubIo io = new StubIo(List.of("hello", "/exit"));
        ChatSession session = session(provider, io, transcripts, catalog());
        session.run();
        assertEquals(1, provider.receivedHistories.get(0).size());
        assertEquals(Role.USER, provider.receivedHistories.get(0).get(0).role());
        assertEquals("hello", provider.receivedHistories.get(0).get(0).content());
        assertTrue(io.lines.stream().anyMatch(l -> l.contains("hello response")));
    }

    @Test
    void keepsContextAcrossTurns() throws Exception {
        FakeProvider provider = new FakeProvider();
        FakeTranscriptWriter transcripts = new FakeTranscriptWriter();
        StubIo io = new StubIo(List.of("first", "second", "/exit"));
        ChatSession session = session(provider, io, transcripts, catalog());
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
        FakeTranscriptWriter transcripts = new FakeTranscriptWriter();
        StubIo io = new StubIo(List.of("first", "/reset", "second", "/exit"));
        ChatSession session = session(provider, io, transcripts, catalog());
        session.run();
        List<ChatMessage> secondTurn = provider.receivedHistories.get(1);
        assertEquals(1, secondTurn.size());
        assertEquals("second", secondTurn.get(0).content());
    }

    @Test
    void unknownCommandIsNotSentToProvider() throws Exception {
        FakeProvider provider = new FakeProvider();
        FakeTranscriptWriter transcripts = new FakeTranscriptWriter();
        StubIo io = new StubIo(List.of("/bogus", "/exit"));
        ChatSession session = session(provider, io, transcripts, catalog());
        session.run();
        assertTrue(provider.receivedHistories.isEmpty());
        assertTrue(io.lines.stream().anyMatch(l -> l.contains("Unknown command")));
    }

    @Test
    void providerErrorIsShownAndLoopContinues() throws Exception {
        Provider failing = (history, tools, sink, reasoningSink) -> {
            throw new ProviderException("HTTP 401: bad key");
        };
        FakeProvider ok = new FakeProvider();
        FakeTranscriptWriter transcripts = new FakeTranscriptWriter();
        StubIo io = new StubIo(List.of("hello", "again", "/exit"));
        ChatSession session = session(new FirstThenProvider(failing, ok), io, transcripts, catalog());
        session.run();
        assertTrue(io.lines.stream().anyMatch(l -> l.contains("HTTP 401")));
        assertEquals(1, ok.calls);
    }

    @Test
    void partialContentFromInterruptedStreamIsKeptInHistory() throws Exception {
        Provider interrupted = (history, tools, sink, reasoningSink) -> {
            sink.accept("partial");
            throw new ProviderException("Stream interrupted", null, "partial");
        };
        FakeProvider ok = new FakeProvider();
        FakeTranscriptWriter transcripts = new FakeTranscriptWriter();
        StubIo io = new StubIo(List.of("hello", "again", "/exit"));
        ChatSession session = session(new FirstThenProvider(interrupted, ok), io, transcripts, catalog());
        session.run();
        List<ChatMessage> secondTurn = ok.receivedHistories.get(0);
        assertEquals(3, secondTurn.size());
        assertEquals(Role.ASSISTANT, secondTurn.get(1).role());
        assertEquals("partial", secondTurn.get(1).content());
    }

    @Test
    void genericProviderFailureIsShownAndLoopContinues() throws Exception {
        Provider failing = (history, tools, sink, reasoningSink) -> {
            throw new IllegalStateException("boom");
        };
        FakeProvider ok = new FakeProvider();
        FakeTranscriptWriter transcripts = new FakeTranscriptWriter();
        StubIo io = new StubIo(List.of("hello", "again", "/exit"));
        ChatSession session = session(new FirstThenProvider(failing, ok), io, transcripts, catalog());
        session.run();
        assertTrue(io.lines.stream().anyMatch(l -> l.contains("boom")));
        assertEquals(1, ok.calls);
    }

    @Test
    void printsPerTurnUsageLine() throws Exception {
        FakeProvider provider = new FakeProvider(new Usage(1200, 300), false);
        FakeTranscriptWriter transcripts = new FakeTranscriptWriter();
        StubIo io = new StubIo(List.of("hello", "/exit"));
        ChatSession session = session(provider, io, transcripts, catalog());
        session.run();
        assertTrue(io.lines.contains("tokens: 1,200 in · 300 out · total 1,500 · session 1,500"));
    }

    @Test
    void usageLineFlagsEstimates() throws Exception {
        FakeProvider provider = new FakeProvider(new Usage(100, 50), true);
        FakeTranscriptWriter transcripts = new FakeTranscriptWriter();
        StubIo io = new StubIo(List.of("hello", "/exit"));
        ChatSession session = session(provider, io, transcripts, catalog());
        session.run();
        assertTrue(io.lines.contains("tokens: 100 in (est.) · 50 out (est.) · total 150 · session 150 (est.)"));
    }

    @Test
    void usageCommandPrintsReport() throws Exception {
        FakeProvider provider = new FakeProvider(new Usage(1200, 300), false);
        FakeTranscriptWriter transcripts = new FakeTranscriptWriter();
        StubIo io = new StubIo(List.of("hello", "/usage", "/exit"));
        ChatSession session = session(provider, io, transcripts, catalog());
        session.run();
        assertTrue(io.lines.stream().anyMatch(l -> l.contains("Session usage:")));
        assertTrue(io.lines.stream().anyMatch(l -> l.contains("  total:       1,500")));
        assertTrue(io.lines.stream().anyMatch(l -> l.contains("  history: 2 messages")));
    }

    @Test
    void warnsAtEightyFiveAndHundredPercent() throws Exception {
        FakeProvider provider = new FakeProvider(new Usage(900, 0), false);
        FakeTranscriptWriter transcripts = new FakeTranscriptWriter();
        StubIo io = new StubIo(List.of("hello", "again", "once more", "/exit"));
        ChatSession session = session(provider, io, transcripts, catalog(null, 1000));
        session.run();
        assertTrue(io.lines.stream().anyMatch(l -> l.contains("session at 90% of your configured 1,000-token context limit")));
        assertTrue(io.lines.stream().anyMatch(l -> l.contains("session reached 100% of your configured 1,000-token context limit")));
    }

    @Test
    void warnsOncePerThreshold() throws Exception {
        FakeProvider provider = new FakeProvider(new Usage(900, 0), false);
        FakeTranscriptWriter transcripts = new FakeTranscriptWriter();
        StubIo io = new StubIo(List.of("a", "b", "c", "d", "e", "/exit"));
        ChatSession session = session(provider, io, transcripts, catalog(null, 1000));
        session.run();
        long warnings = io.lines.stream().filter(l -> l.startsWith("Warning:")).count();
        assertEquals(2, warnings);
    }

    @Test
    void resetClearsUsageTracker() throws Exception {
        FakeProvider provider = new FakeProvider(new Usage(900, 0), false);
        FakeTranscriptWriter transcripts = new FakeTranscriptWriter();
        StubIo io = new StubIo(List.of("hello", "/reset", "/usage", "/exit"));
        ChatSession session = session(provider, io, transcripts, catalog(null, 1000));
        session.run();
        assertTrue(io.lines.stream().anyMatch(l -> l.contains("  total:       0")));
        assertTrue(io.lines.stream().anyMatch(l -> l.contains("  history: 0 messages")));
    }

    @Test
    void usageReportShowsContextLimitWhenConfigured() throws Exception {
        FakeProvider provider = new FakeProvider(new Usage(1200, 300), false);
        FakeTranscriptWriter transcripts = new FakeTranscriptWriter();
        StubIo io = new StubIo(List.of("hello", "/usage", "/exit"));
        ChatSession session = session(provider, io, transcripts, catalog(null, 128000));
        session.run();
        assertTrue(io.lines.stream().anyMatch(l -> l.contains("  context limit: 128,000 configured (1% used)")));
    }

    @Test
    void streamsReasoningThroughIo() throws Exception {
        FakeProvider provider = new FakeProvider(new Usage(100, 50), true, "ponder");
        FakeTranscriptWriter transcripts = new FakeTranscriptWriter();
        StubIo io = new StubIo(List.of("hello", "/exit"));
        ChatSession session = session(provider, io, transcripts, catalog());
        session.run();
        assertTrue(io.lines.contains("ponder"));
    }

    @Test
    void thinkingIsNotSentToProvider() throws Exception {
        FakeProvider ok = new FakeProvider(new Usage(0, 0), false, "ponder");
        FakeTranscriptWriter transcripts = new FakeTranscriptWriter();
        StubIo io = new StubIo(List.of("first", "second", "/exit"));
        ChatSession session = session(ok, io, transcripts, catalog());
        session.run();
        List<ChatMessage> secondTurn = ok.receivedHistories.get(1);
        assertEquals(3, secondTurn.size());
        assertEquals("first response", secondTurn.get(1).content());
        assertNull(secondTurn.get(1).thinking());
    }

    @Test
    void includesSystemMessageInContext() throws Exception {
        FakeProvider provider = new FakeProvider();
        FakeTranscriptWriter transcripts = new FakeTranscriptWriter();
        StubIo io = new StubIo(List.of("hello", "/exit"));
        ChatSession session = session(provider, io, transcripts, catalog("You are helpful", null));
        session.run();
        List<ChatMessage> context = provider.receivedHistories.get(0);
        assertEquals(2, context.size());
        assertEquals(Role.SYSTEM, context.get(0).role());
        assertEquals("You are helpful", context.get(0).content());
    }

    @Test
    void interruptedReasoningPreservesPartialThinking() throws Exception {
        Provider interrupted = (history, tools, sink, reasoningSink) -> {
            reasoningSink.accept("half");
            throw new ProviderException("Stream interrupted", null, null, "half");
        };
        FakeProvider ok = new FakeProvider(new Usage(0, 0), false);
        FakeTranscriptWriter transcripts = new FakeTranscriptWriter();
        StubIo io = new StubIo(List.of("hello", "again", "/exit"));
        ChatSession session = session(new FirstThenProvider(interrupted, ok), io, transcripts, catalog());
        session.run();
        List<ChatMessage> secondTurn = ok.receivedHistories.get(0);
        assertEquals(3, secondTurn.size());
        assertEquals(Role.ASSISTANT, secondTurn.get(1).role());
        assertNull(secondTurn.get(1).thinking());
        assertEquals(Arrays.asList("half", null), transcripts.assistantThinkings);
    }

    @Test
    void startsSessionAndPrintsUuid() throws Exception {
        FakeProvider provider = new FakeProvider();
        FakeTranscriptWriter transcripts = new FakeTranscriptWriter();
        StubIo io = new StubIo(List.of("/exit"));
        ChatSession session = session(provider, io, transcripts, catalog());
        session.run();
        assertEquals(1, transcripts.starts.size());
        assertTrue(io.lines.stream().anyMatch(l -> l.startsWith("Session: ")));
    }

    @Test
    void recordsUserAndAssistantTurns() throws Exception {
        FakeProvider provider = new FakeProvider(new Usage(1200, 300), false);
        FakeTranscriptWriter transcripts = new FakeTranscriptWriter();
        StubIo io = new StubIo(List.of("hello", "/exit"));
        ChatSession session = session(provider, io, transcripts, catalog());
        session.run();
        assertEquals(List.of("hello"), transcripts.userContents);
        assertEquals(List.of("hello response"), transcripts.assistantContents);
        assertEquals(new Usage(1200, 300), transcripts.assistantUsages.get(0));
        assertEquals(false, transcripts.assistantEstimated.get(0));
    }

    @Test
    void recordsThinkingOnAssistantTurn() throws Exception {
        FakeProvider provider = new FakeProvider(new Usage(0, 0), false, "ponder");
        FakeTranscriptWriter transcripts = new FakeTranscriptWriter();
        StubIo io = new StubIo(List.of("hello", "/exit"));
        ChatSession session = session(provider, io, transcripts, catalog());
        session.run();
        assertEquals(List.of("ponder"), transcripts.assistantThinkings);
    }

    @Test
    void resetStartsNewSession() throws Exception {
        FakeProvider provider = new FakeProvider();
        FakeTranscriptWriter transcripts = new FakeTranscriptWriter();
        StubIo io = new StubIo(List.of("/reset", "/exit"));
        ChatSession session = session(provider, io, transcripts, catalog());
        session.run();
        assertEquals(2, transcripts.starts.size());
    }

    @Test
    void recordsPartialContentOnInterruption() throws Exception {
        Provider interrupted = (history, tools, sink, reasoningSink) -> {
            sink.accept("partial");
            throw new ProviderException("Stream interrupted", null, "partial");
        };
        FakeProvider ok = new FakeProvider(new Usage(0, 0), false);
        FakeTranscriptWriter transcripts = new FakeTranscriptWriter();
        StubIo io = new StubIo(List.of("hello", "/exit"));
        ChatSession session = session(new FirstThenProvider(interrupted, ok), io, transcripts, catalog());
        session.run();
        assertEquals(List.of("partial"), transcripts.assistantContents);
        assertTrue(transcripts.assistantUsages.get(0) == null);
    }

    @Test
    void continuesWhenTranscriptStartFails() throws Exception {
        FakeProvider provider = new FakeProvider();
        FakeTranscriptWriter transcripts = new FakeTranscriptWriter();
        transcripts.failStart = true;
        StubIo io = new StubIo(List.of("hello", "/exit"));
        ChatSession session = session(provider, io, transcripts, catalog());
        session.run();
        assertEquals(1, provider.calls);
        assertTrue(transcripts.userContents.isEmpty());
    }

    @Test
    void continuesWhenTranscriptAppendFails() throws Exception {
        FakeProvider provider = new FakeProvider();
        FakeTranscriptWriter transcripts = new FakeTranscriptWriter();
        transcripts.failAppend = true;
        StubIo io = new StubIo(List.of("hello", "again", "/exit"));
        ChatSession session = session(provider, io, transcripts, catalog());
        session.run();
        assertEquals(2, provider.calls);
        assertTrue(io.lines.stream().anyMatch(l -> l.contains("again response")));
        assertEquals(1, transcripts.appendAttempts);
    }

    @Test
    void bannerShowsAgentName() throws Exception {
        FakeProvider provider = new FakeProvider();
        FakeTranscriptWriter transcripts = new FakeTranscriptWriter();
        StubIo io = new StubIo(List.of("/exit"));
        ChatSession session = session(provider, io, transcripts, catalog());
        session.run();
        assertTrue(io.lines.stream().anyMatch(l -> l.startsWith("Agent: a")));
    }

    @Test
    void agentSwitchRebuildsProviderAndStartsNewSession() throws Exception {
        AgentCatalog catalog = new AgentCatalog(
                List.of(new ProviderConfig("p", "sk-test", "https://example.com/v1")),
                List.of(new AgentConfig("a", "p", "model-a", null, null),
                        new AgentConfig("b", "p", "model-b", null, null)),
                "a", true, Path.of("sessions"));
        FakeProviderFactory factory = new FakeProviderFactory(new FakeProvider());
        FakeTranscriptWriter transcripts = new FakeTranscriptWriter();
        StubIo io = new StubIo(List.of("/agent b", "hello", "/exit"));
        ChatSession session = new ChatSession(io, transcripts, new FullContextBuilder(),
                catalog, factory, "a");
        session.run();
        assertEquals(2, factory.calls);
        assertEquals(2, transcripts.starts.size());
        assertTrue(io.lines.stream().anyMatch(l -> l.startsWith("Agent: b")));
    }

    @Test
    void unknownAgentSwitchIsRejected() throws Exception {
        FakeProvider provider = new FakeProvider();
        FakeProviderFactory factory = new FakeProviderFactory(provider);
        FakeTranscriptWriter transcripts = new FakeTranscriptWriter();
        StubIo io = new StubIo(List.of("/agent nope", "/exit"));
        ChatSession session = new ChatSession(io, transcripts, new FullContextBuilder(),
                catalog(), factory, "a");
        session.run();
        assertTrue(io.lines.stream().anyMatch(l -> l.contains("Unknown agent: nope")));
        assertEquals(1, factory.calls);
    }

    @Test
    void agentsCommandListsNames() throws Exception {
        AgentCatalog catalog = new AgentCatalog(
                List.of(new ProviderConfig("p", "sk-test", "https://example.com/v1")),
                List.of(new AgentConfig("a", "p", "m", null, null),
                        new AgentConfig("b", "p", "m", null, null)),
                "a", true, Path.of("sessions"));
        FakeProvider provider = new FakeProvider();
        FakeTranscriptWriter transcripts = new FakeTranscriptWriter();
        StubIo io = new StubIo(List.of("/agents", "/exit"));
        ChatSession session = new ChatSession(io, transcripts, new FullContextBuilder(),
                catalog, new FakeProviderFactory(provider), "a");
        session.run();
        assertTrue(io.lines.stream().anyMatch(l -> l.contains("a") && l.contains("b")));
    }

    private AgentCatalog catalog() {
        return catalog(null, null);
    }

    private AgentCatalog catalog(String systemPrompt, Integer maxContext) {
        return new AgentCatalog(
                List.of(new ProviderConfig("p", "sk-test", "https://example.com/v1")),
                List.of(new AgentConfig("a", "p", "test-model", systemPrompt, maxContext)),
                "a", true, Path.of("sessions"));
    }

    private ChatSession session(Provider provider, StubIo io, FakeTranscriptWriter transcripts,
                                AgentCatalog catalog) {
        return new ChatSession(io, transcripts, new FullContextBuilder(), catalog,
                new FakeProviderFactory(provider), "a");
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

        @Override
        public void writeReasoning(String text) {
            lines.add(text);
        }
    }

    static class FakeProvider implements Provider {
        final Usage turnUsage;
        final boolean estimated;
        final String thinking;
        final List<List<ChatMessage>> receivedHistories = new ArrayList<>();
        int calls = 0;

        FakeProvider() {
            this(new Usage(0, 0), false, null);
        }

        FakeProvider(Usage turnUsage, boolean estimated) {
            this(turnUsage, estimated, null);
        }

        FakeProvider(Usage turnUsage, boolean estimated, String thinking) {
            this.turnUsage = turnUsage;
            this.estimated = estimated;
            this.thinking = thinking;
        }

        @Override
        public ProviderResponse send(List<ChatMessage> history, List<Tool> tools, Consumer<String> tokenSink,
                                     Consumer<String> reasoningSink) {
            receivedHistories.add(new ArrayList<>(history));
            calls++;
            ChatMessage last = history.get(history.size() - 1);
            String reply = last.content() + " response";
            tokenSink.accept(reply);
            if (thinking != null) {
                reasoningSink.accept(thinking);
            }
            return new ProviderResponse(new ChatMessage(Role.ASSISTANT, reply, thinking), turnUsage, estimated);
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
        public ProviderResponse send(List<ChatMessage> history, List<Tool> tools, Consumer<String> tokenSink,
                                     Consumer<String> reasoningSink) {
            if (calls++ == 0) {
                return first.send(history, tools, tokenSink, reasoningSink);
            }
            return then.send(history, tools, tokenSink, reasoningSink);
        }
    }

    static class FakeProviderFactory implements ProviderFactory {
        final Provider provider;
        int calls = 0;

        FakeProviderFactory(Provider provider) {
            this.provider = provider;
        }

        @Override
        public Provider create(AppConfig config) {
            calls++;
            return provider;
        }
    }

    static class FakeTranscriptWriter implements TranscriptWriter {
        final List<UUID> starts = new ArrayList<>();
        final List<String> userContents = new ArrayList<>();
        final List<String> assistantContents = new ArrayList<>();
        final List<String> assistantThinkings = new ArrayList<>();
        final List<Usage> assistantUsages = new ArrayList<>();
        final List<Boolean> assistantEstimated = new ArrayList<>();
        final List<String> toolCallIds = new ArrayList<>();
        final List<String> toolResultIds = new ArrayList<>();
        final List<String> toolResultContents = new ArrayList<>();
        boolean failStart;
        boolean failAppend;
        int appendAttempts;

        @Override
        public void start(UUID sessionId) throws IOException {
            if (failStart) {
                throw new IOException("boom");
            }
            starts.add(sessionId);
        }

        @Override
        public void appendUser(UUID sessionId, String content) throws IOException {
            appendAttempts++;
            if (failAppend) {
                throw new IOException("boom");
            }
            userContents.add(content);
        }

        @Override
        public void appendAssistant(UUID sessionId, String content, String thinking,
                                    Usage usage, boolean estimated) throws IOException {
            appendAttempts++;
            if (failAppend) {
                throw new IOException("boom");
            }
            assistantContents.add(content);
            assistantThinkings.add(thinking);
            assistantUsages.add(usage);
            assistantEstimated.add(estimated);
        }

        @Override
        public void appendToolCall(UUID sessionId, String id, String name, JsonNode arguments) throws IOException {
            appendAttempts++;
            if (failAppend) {
                throw new IOException("boom");
            }
            toolCallIds.add(id);
        }

        @Override
        public void appendToolResult(UUID sessionId, String id, String content, boolean error) throws IOException {
            appendAttempts++;
            if (failAppend) {
                throw new IOException("boom");
            }
            toolResultIds.add(id);
            toolResultContents.add(content);
        }
    }
}
