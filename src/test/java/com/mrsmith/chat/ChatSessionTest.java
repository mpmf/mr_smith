package com.mrsmith.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.mrsmith.provider.ToolCall;
import com.mrsmith.provider.Usage;
import com.mrsmith.session.TranscriptWriter;
import com.mrsmith.skill.SkillCatalog;
import com.mrsmith.tool.TaskResult;
import com.mrsmith.tool.TaskRunner;
import com.mrsmith.tool.Tool;
import com.mrsmith.tool.ToolRegistry;
import com.mrsmith.tool.ToolRegistryFactory;
import com.mrsmith.tool.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
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

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDir;

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
                catalog, factory, noToolsFactory(), emptySkills(), "a");
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
                catalog(), factory, noToolsFactory(), emptySkills(), "a");
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
                catalog, new FakeProviderFactory(provider), noToolsFactory(), emptySkills(), "a");
        session.run();
        assertTrue(io.lines.stream().anyMatch(l -> l.contains("a") && l.contains("b")));
    }

    @Test
    void runsToolLoopAndFeedsResultBack() throws Exception {
        FakeTool readFile = new FakeTool("read_file", true, new ToolResult("file contents", false));
        ToolRegistryFactory registryFactory = (config, catalog, io, taskRunner) -> new ToolRegistry(List.of(readFile));
        FakeToolProvider toolProvider = new FakeToolProvider(
                new ToolCall("call_1", "read_file", JSON.readTree("{\"path\":\"a.txt\"}")),
                "final answer");
        FakeTranscriptWriter transcripts = new FakeTranscriptWriter();
        StubIo io = new StubIo(List.of("hello", "/exit"));
        ChatSession session = new ChatSession(io, transcripts, new FullContextBuilder(),
                catalog(), new FakeProviderFactory(toolProvider), registryFactory, emptySkills(), "a");
        session.run();
        assertEquals(2, toolProvider.calls);
        assertEquals(1, readFile.calls);
        List<ChatMessage> secondSend = toolProvider.receivedHistories.get(1);
        assertEquals(Role.TOOL, secondSend.get(secondSend.size() - 1).role());
        assertEquals("file contents", secondSend.get(secondSend.size() - 1).content());
        assertTrue(io.lines.stream().anyMatch(l -> l.contains("tool: read_file(a.txt) -> ok")));
        assertEquals(1, transcripts.toolCallIds.size());
        assertEquals("call_1", transcripts.toolCallIds.get(0));
        assertTrue(io.lines.stream().anyMatch(l -> l.contains("final answer")));
    }

    @Test
    void declinesNonReadOnlyTool() throws Exception {
        FakeTool shell = new FakeTool("shell", false, new ToolResult("ran", false));
        ToolRegistryFactory registryFactory = (config, catalog, io, taskRunner) -> new ToolRegistry(List.of(shell));
        FakeToolProvider toolProvider = new FakeToolProvider(
                new ToolCall("call_2", "shell", JSON.readTree("{\"command\":\"rm -rf /\"}")),
                "answer after decline");
        FakeTranscriptWriter transcripts = new FakeTranscriptWriter();
        StubIo io = new StubIo(List.of("hello", "n", "/exit"));
        ChatSession session = new ChatSession(io, transcripts, new FullContextBuilder(),
                catalog(), new FakeProviderFactory(toolProvider), registryFactory, emptySkills(), "a");
        session.run();
        assertEquals(0, shell.calls);
        List<ChatMessage> secondSend = toolProvider.receivedHistories.get(1);
        ChatMessage last = secondSend.get(secondSend.size() - 1);
        assertEquals(Role.TOOL, last.role());
        assertTrue(last.content().contains("declined"));
    }

    @Test
    void confirmsNonReadOnlyToolOnYes() throws Exception {
        FakeTool shell = new FakeTool("shell", false, new ToolResult("ran", false));
        ToolRegistryFactory registryFactory = (config, catalog, io, taskRunner) -> new ToolRegistry(List.of(shell));
        FakeToolProvider toolProvider = new FakeToolProvider(
                new ToolCall("call_3", "shell", JSON.readTree("{\"command\":\"echo hi\"}")),
                "answer");
        FakeTranscriptWriter transcripts = new FakeTranscriptWriter();
        StubIo io = new StubIo(List.of("hello", "y", "/exit"));
        ChatSession session = new ChatSession(io, transcripts, new FullContextBuilder(),
                catalog(), new FakeProviderFactory(toolProvider), registryFactory, emptySkills(), "a");
        session.run();
        assertEquals(1, shell.calls);
    }

    @Test
    void unknownToolProducesErrorResult() throws Exception {
        ToolRegistryFactory registryFactory = (config, catalog, io, taskRunner) -> new ToolRegistry(List.of());
        FakeToolProvider toolProvider = new FakeToolProvider(
                new ToolCall("call_4", "nonexistent", JSON.readTree("{}")),
                "answer");
        FakeTranscriptWriter transcripts = new FakeTranscriptWriter();
        StubIo io = new StubIo(List.of("hello", "/exit"));
        ChatSession session = new ChatSession(io, transcripts, new FullContextBuilder(),
                catalog(), new FakeProviderFactory(toolProvider), registryFactory, emptySkills(), "a");
        session.run();
        List<ChatMessage> secondSend = toolProvider.receivedHistories.get(1);
        ChatMessage last = secondSend.get(secondSend.size() - 1);
        assertEquals(Role.TOOL, last.role());
        assertTrue(last.content().contains("Unknown tool: nonexistent"));
        assertTrue(io.lines.stream().anyMatch(l -> l.contains("tool: nonexistent() -> error")));
    }

    @Test
    void stopsAtToolRoundLimit() throws Exception {
        FakeTool tool = new FakeTool("read_file", true, new ToolResult("data", false));
        ToolRegistryFactory registryFactory = (config, catalog, io, taskRunner) -> new ToolRegistry(List.of(tool));
        FakeToolProvider provider = new FakeToolProvider();
        provider.alwaysCall("read_file", JSON.readTree("{\"path\":\"a.txt\"}"));
        FakeTranscriptWriter transcripts = new FakeTranscriptWriter();
        StubIo io = new StubIo(List.of("hello", "/exit"));
        ChatSession session = new ChatSession(io, transcripts, new FullContextBuilder(),
                catalog(), new FakeProviderFactory(provider), registryFactory, emptySkills(), "a");
        session.run();
        assertEquals(10, provider.calls);
        List<ChatMessage> lastSend = provider.receivedHistories.get(provider.receivedHistories.size() - 1);
        ChatMessage last = lastSend.get(lastSend.size() - 1);
        assertEquals(Role.TOOL, last.role());
        assertTrue(last.content().contains("round limit"));
        assertEquals("call_x", last.toolCallId());
        assertTrue(transcripts.toolResultContents.stream().anyMatch(c -> c.contains("round limit")));
    }

    @Test
    void noToolsAgentSendsEmptyToolsList() throws Exception {
        FakeProvider provider = new FakeProvider();
        ToolRegistryFactory registryFactory = (config, catalog, io, taskRunner) -> new ToolRegistry(List.of());
        FakeTranscriptWriter transcripts = new FakeTranscriptWriter();
        StubIo io = new StubIo(List.of("hello", "/exit"));
        ChatSession session = new ChatSession(io, transcripts, new FullContextBuilder(),
                catalog(), new FakeProviderFactory(provider), registryFactory, emptySkills(), "a");
        session.run();
        assertTrue(provider.receivedTools.get(0).isEmpty());
    }

    @Test
    void systemPromptIncludesSkillsIndex() throws Exception {
        FakeProvider provider = new FakeProvider();
        FakeTranscriptWriter transcripts = new FakeTranscriptWriter();
        StubIo io = new StubIo(List.of("hello", "/exit"));
        ChatSession session = session(provider, io, transcripts, catalog("You are helpful", null),
                skillsCatalog("coding", "Write Java."));
        session.run();
        List<ChatMessage> context = provider.receivedHistories.get(0);
        assertEquals(Role.SYSTEM, context.get(0).role());
        assertTrue(context.get(0).content().startsWith("You are helpful"));
        assertTrue(context.get(0).content().contains("Available skills:"));
        assertTrue(context.get(0).content().contains("coding: Write Java."));
    }

    @Test
    void toolsLessAgentStillGetsSkillTool() throws Exception {
        SkillCatalog skills = skillsCatalog("coding", "Write Java.");
        ToolRegistryFactory registryFactory = (config, catalog, io, taskRunner) -> ToolRegistry.with(List.of(), catalog, io, taskRunner);
        FakeProvider provider = new FakeProvider();
        FakeTranscriptWriter transcripts = new FakeTranscriptWriter();
        StubIo io = new StubIo(List.of("hello", "/exit"));
        ChatSession session = new ChatSession(io, transcripts, new FullContextBuilder(),
                catalog(), new FakeProviderFactory(provider), registryFactory, skills, "a");
        session.run();
        assertTrue(provider.receivedTools.get(0).stream().anyMatch(t -> t.name().equals("skill")));
    }

    @Test
    void resetClearsLoadedSkills() throws Exception {
        SkillCatalog skills = skillsCatalog("coding", "Write Java.");
        ToolRegistryFactory registryFactory = (config, catalog, io, taskRunner) -> ToolRegistry.with(List.of(), catalog, io, taskRunner);
        FakeProvider provider = new FakeProvider();
        FakeTranscriptWriter transcripts = new FakeTranscriptWriter();
        StubIo io = new StubIo(List.of("/skills coding", "/reset", "/skills coding", "/exit"));
        ChatSession session = new ChatSession(io, transcripts, new FullContextBuilder(),
                catalog(), new FakeProviderFactory(provider), registryFactory, skills, "a");
        session.run();
        long loaded = io.lines.stream().filter(l -> l.contains("Loaded skill: coding")).count();
        assertEquals(2, loaded);
    }

    @Test
    void skillsCommandListsSkills() throws Exception {
        FakeProvider provider = new FakeProvider();
        FakeTranscriptWriter transcripts = new FakeTranscriptWriter();
        StubIo io = new StubIo(List.of("/skills", "/exit"));
        ChatSession session = session(provider, io, transcripts, catalog(),
                skillsCatalog("coding", "Write Java."));
        session.run();
        assertTrue(provider.receivedHistories.isEmpty());
        assertTrue(io.lines.stream().anyMatch(l -> l.contains("coding") && l.contains("Write Java.")));
    }

    @Test
    void skillsCommandLoadsSkill() throws Exception {
        SkillCatalog skills = skillsCatalog("coding", "Write Java.");
        ToolRegistryFactory registryFactory = (config, catalog, io, taskRunner) -> ToolRegistry.with(List.of(), catalog, io, taskRunner);
        FakeProvider provider = new FakeProvider();
        FakeTranscriptWriter transcripts = new FakeTranscriptWriter();
        StubIo io = new StubIo(List.of("/skills coding", "hello", "/exit"));
        ChatSession session = new ChatSession(io, transcripts, new FullContextBuilder(),
                catalog(), new FakeProviderFactory(provider), registryFactory, skills, "a");
        session.run();
        assertTrue(io.lines.stream().anyMatch(l -> l.contains("Loaded skill: coding")));
        assertEquals(List.of("coding"), transcripts.skillLoads);
        List<ChatMessage> context = provider.receivedHistories.get(0);
        assertTrue(context.stream().anyMatch(m -> m.role() == Role.SYSTEM
                && m.content().startsWith("## coding")));
    }

    @Test
    void skillsCommandUnknownSkill() throws Exception {
        FakeProvider provider = new FakeProvider();
        FakeTranscriptWriter transcripts = new FakeTranscriptWriter();
        StubIo io = new StubIo(List.of("/skills nope", "/exit"));
        ChatSession session = session(provider, io, transcripts, catalog(),
                skillsCatalog("coding", "Write Java."));
        session.run();
        assertTrue(io.lines.stream().anyMatch(l -> l.contains("Unknown skill: nope")));
        assertTrue(transcripts.skillLoads.isEmpty());
    }

    @Test
    void manualLoadDedupesWithToolLoad() throws Exception {
        SkillCatalog skills = skillsCatalog("coding", "Write Java.");
        ToolRegistryFactory registryFactory = (config, catalog, io, taskRunner) -> ToolRegistry.with(List.of(), catalog, io, taskRunner);
        FakeToolProvider toolProvider = new FakeToolProvider(
                new ToolCall("call_5", "skill", JSON.readTree("{\"name\":\"coding\"}")),
                "answer");
        FakeTranscriptWriter transcripts = new FakeTranscriptWriter();
        StubIo io = new StubIo(List.of("hello", "/skills coding", "/exit"));
        ChatSession session = new ChatSession(io, transcripts, new FullContextBuilder(),
                catalog(), new FakeProviderFactory(toolProvider), registryFactory, skills, "a");
        session.run();
        assertTrue(io.lines.stream().anyMatch(l -> l.contains("Skill 'coding' is already loaded.")));
    }

    @Test
    void helpListsSkillsCommand() throws Exception {
        FakeProvider provider = new FakeProvider();
        FakeTranscriptWriter transcripts = new FakeTranscriptWriter();
        StubIo io = new StubIo(List.of("/help", "/exit"));
        ChatSession session = session(provider, io, transcripts, catalog());
        session.run();
        assertTrue(io.lines.stream().anyMatch(l -> l.contains("/skills")));
    }

    @Test
    void editRequiresApproval() throws Exception {
        FakeTool edit = new FakeTool("edit", false, new ToolResult("Edited x", false));
        ToolRegistryFactory registryFactory = (config, catalog, io, taskRunner) -> new ToolRegistry(List.of(edit));
        FakeToolProvider toolProvider = new FakeToolProvider(
                new ToolCall("call_e1", "edit",
                        JSON.readTree("{\"filePath\":\"a.txt\",\"oldString\":\"x\",\"newString\":\"y\"}")),
                "answer");
        FakeTranscriptWriter transcripts = new FakeTranscriptWriter();
        StubIo io = new StubIo(List.of("hello", "n", "/exit"));
        ChatSession session = new ChatSession(io, transcripts, new FullContextBuilder(),
                catalog(), new FakeProviderFactory(toolProvider), registryFactory, emptySkills(), "a");
        session.run();
        assertEquals(0, edit.calls);
        List<ChatMessage> secondSend = toolProvider.receivedHistories.get(1);
        ChatMessage last = secondSend.get(secondSend.size() - 1);
        assertTrue(last.content().contains("declined"));
    }

    @Test
    void todowriteRunsWithoutApproval() throws Exception {
        ToolRegistryFactory registryFactory = (config, catalog, io, taskRunner) -> ToolRegistry.with(List.of(), catalog, io, taskRunner);
        FakeToolProvider toolProvider = new FakeToolProvider(
                new ToolCall("call_t1", "todowrite",
                        JSON.readTree("{\"todos\":[{\"content\":\"a\",\"status\":\"in_progress\",\"priority\":\"high\"}]}")),
                "ok");
        FakeTranscriptWriter transcripts = new FakeTranscriptWriter();
        StubIo io = new StubIo(List.of("hello", "/exit"));
        ChatSession session = new ChatSession(io, transcripts, new FullContextBuilder(),
                catalog(), new FakeProviderFactory(toolProvider), registryFactory, emptySkills(), "a");
        session.run();
        assertTrue(io.lines.stream().anyMatch(l -> l.contains("tool: todowrite() -> ok")));
        List<ChatMessage> secondSend = toolProvider.receivedHistories.get(1);
        ChatMessage last = secondSend.get(secondSend.size() - 1);
        assertEquals(Role.TOOL, last.role());
        assertTrue(last.content().contains("in_progress"));
    }

    @Test
    void questionReadsAnswerWithoutApproval() throws Exception {
        ToolRegistryFactory registryFactory = (config, catalog, io, taskRunner) -> ToolRegistry.with(List.of(), catalog, io, taskRunner);
        FakeToolProvider toolProvider = new FakeToolProvider(
                new ToolCall("call_q1", "question",
                        JSON.readTree("{\"questions\":[{\"question\":\"Pick\",\"options\":[{\"label\":\"A\"},{\"label\":\"B\"}]}]}")),
                "chosen");
        FakeTranscriptWriter transcripts = new FakeTranscriptWriter();
        StubIo io = new StubIo(List.of("hello", "2", "/exit"));
        ChatSession session = new ChatSession(io, transcripts, new FullContextBuilder(),
                catalog(), new FakeProviderFactory(toolProvider), registryFactory, emptySkills(), "a");
        session.run();
        List<ChatMessage> secondSend = toolProvider.receivedHistories.get(1);
        ChatMessage last = secondSend.get(secondSend.size() - 1);
        assertEquals(Role.TOOL, last.role());
        assertEquals("[\"B\"]", last.content());
    }

    @Test
    void tasksCommandShowsEmptyList() throws Exception {
        ToolRegistryFactory registryFactory = (config, catalog, io, taskRunner) -> ToolRegistry.with(List.of(), catalog, io, taskRunner);
        FakeProvider provider = new FakeProvider();
        FakeTranscriptWriter transcripts = new FakeTranscriptWriter();
        StubIo io = new StubIo(List.of("/tasks", "/exit"));
        ChatSession session = new ChatSession(io, transcripts, new FullContextBuilder(),
                catalog(), new FakeProviderFactory(provider), registryFactory, emptySkills(), "a");
        session.run();
        assertTrue(io.lines.stream().anyMatch(l -> l.contains("No tasks.")));
        assertTrue(provider.receivedHistories.isEmpty());
    }

    @Test
    void tasksCommandListsTasks() throws Exception {
        ToolRegistryFactory registryFactory = (config, catalog, io, taskRunner) -> ToolRegistry.with(List.of(), catalog, io, taskRunner);
        FakeToolProvider toolProvider = new FakeToolProvider(
                new ToolCall("call_t2", "todowrite",
                        JSON.readTree("{\"todos\":[{\"content\":\"implement edit\",\"status\":\"in_progress\",\"priority\":\"high\"},{\"content\":\"write tests\",\"status\":\"pending\",\"priority\":\"low\"}]}")),
                "done");
        FakeTranscriptWriter transcripts = new FakeTranscriptWriter();
        StubIo io = new StubIo(List.of("hello", "/tasks", "/exit"));
        ChatSession session = new ChatSession(io, transcripts, new FullContextBuilder(),
                catalog(), new FakeProviderFactory(toolProvider), registryFactory, emptySkills(), "a");
        session.run();
        assertTrue(io.lines.stream().anyMatch(l -> l.contains("in_progress high  implement edit")));
        assertTrue(io.lines.stream().anyMatch(l -> l.contains("pending low  write tests")));
    }

    @Test
    void resetClearsTaskList() throws Exception {
        ToolRegistryFactory registryFactory = (config, catalog, io, taskRunner) -> ToolRegistry.with(List.of(), catalog, io, taskRunner);
        FakeToolProvider toolProvider = new FakeToolProvider(
                new ToolCall("call_t3", "todowrite",
                        JSON.readTree("{\"todos\":[{\"content\":\"a\",\"status\":\"pending\",\"priority\":\"high\"}]}")),
                "done");
        FakeTranscriptWriter transcripts = new FakeTranscriptWriter();
        StubIo io = new StubIo(List.of("hello", "/reset", "/tasks", "/exit"));
        ChatSession session = new ChatSession(io, transcripts, new FullContextBuilder(),
                catalog(), new FakeProviderFactory(toolProvider), registryFactory, emptySkills(), "a");
        session.run();
        assertTrue(io.lines.stream().anyMatch(l -> l.contains("No tasks.")));
    }

    @Test
    void toolsLessAgentGetsAlwaysOnTools() throws Exception {
        SkillCatalog skills = skillsCatalog("coding", "Write Java.");
        ToolRegistryFactory registryFactory = (config, catalog, io, taskRunner) -> ToolRegistry.with(List.of(), catalog, io, taskRunner);
        FakeProvider provider = new FakeProvider();
        FakeTranscriptWriter transcripts = new FakeTranscriptWriter();
        StubIo io = new StubIo(List.of("hello", "/exit"));
        ChatSession session = new ChatSession(io, transcripts, new FullContextBuilder(),
                catalog(), new FakeProviderFactory(provider), registryFactory, skills, "a");
        session.run();
        List<String> names = provider.receivedTools.get(0).stream().map(Tool::name).toList();
        assertTrue(names.contains("edit"));
        assertTrue(names.contains("todowrite"));
        assertTrue(names.contains("question"));
        assertTrue(names.contains("skill"));
    }

    @Test
    void helpMentionsTasksCommand() throws Exception {
        FakeProvider provider = new FakeProvider();
        FakeTranscriptWriter transcripts = new FakeTranscriptWriter();
        StubIo io = new StubIo(List.of("/help", "/exit"));
        ChatSession session = session(provider, io, transcripts, catalog());
        session.run();
        assertTrue(io.lines.stream().anyMatch(l -> l.contains("/tasks")));
    }

    @Test
    void toolRoundLimitHonorsConfig() throws Exception {
        FakeTool tool = new FakeTool("read_file", true, new ToolResult("data", false));
        ToolRegistryFactory registryFactory = (config, catalog, io, taskRunner) -> new ToolRegistry(List.of(tool));
        FakeToolProvider provider = new FakeToolProvider();
        provider.alwaysCall("read_file", JSON.readTree("{\"path\":\"a.txt\"}"));
        FakeTranscriptWriter transcripts = new FakeTranscriptWriter();
        StubIo io = new StubIo(List.of("hello", "/exit"));
        AgentCatalog catalog = new AgentCatalog(
                List.of(new ProviderConfig("p", "sk-test", "https://example.com/v1")),
                List.of(new AgentConfig("a", "p", "m", null, null, 2)),
                "a", true, Path.of("sessions"));
        ChatSession session = new ChatSession(io, transcripts, new FullContextBuilder(),
                catalog, new FakeProviderFactory(provider), registryFactory, emptySkills(), "a");
        session.run();
        assertEquals(4, provider.calls);
        List<ChatMessage> lastSend = provider.receivedHistories.get(provider.receivedHistories.size() - 1);
        assertTrue(lastSend.get(lastSend.size() - 1).content().contains("round limit (2)"));
    }

    @Test
    void taskToolResultFeedsBack() throws Exception {
        TaskRunner fakeRunner = (prompt, agent, taskId) -> new TaskResult("subagent-1", "all done", false);
        ToolRegistryFactory registryFactory = (config, catalog, io, taskRunner) ->
                ToolRegistry.with(List.of(), catalog, io, fakeRunner);
        FakeToolProvider toolProvider = new FakeToolProvider(
                new ToolCall("call_t", "task", JSON.readTree("{\"description\":\"x\",\"prompt\":\"do it\"}")),
                "answer");
        FakeTranscriptWriter transcripts = new FakeTranscriptWriter();
        StubIo io = new StubIo(List.of("hello", "/exit"));
        ChatSession session = new ChatSession(io, transcripts, new FullContextBuilder(),
                catalog(), new FakeProviderFactory(toolProvider), registryFactory, emptySkills(), "a");
        session.run();
        List<ChatMessage> secondSend = toolProvider.receivedHistories.get(1);
        ChatMessage last = secondSend.get(secondSend.size() - 1);
        assertEquals(Role.TOOL, last.role());
        assertEquals("Subagent subagent-1: all done", last.content());
    }

    @Test
    void toolsLessAgentGetsTaskTool() throws Exception {
        ToolRegistryFactory registryFactory = (config, catalog, io, taskRunner) ->
                ToolRegistry.with(List.of(), catalog, io, taskRunner);
        FakeProvider provider = new FakeProvider();
        FakeTranscriptWriter transcripts = new FakeTranscriptWriter();
        StubIo io = new StubIo(List.of("hello", "/exit"));
        ChatSession session = new ChatSession(io, transcripts, new FullContextBuilder(),
                catalog(), new FakeProviderFactory(provider), registryFactory, emptySkills(), "a");
        session.run();
        assertTrue(provider.receivedTools.get(0).stream().anyMatch(t -> t.name().equals("task")));
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
        return session(provider, io, transcripts, catalog, emptySkills());
    }

    private ChatSession session(Provider provider, StubIo io, FakeTranscriptWriter transcripts,
                                AgentCatalog catalog, SkillCatalog skills) {
        return new ChatSession(io, transcripts, new FullContextBuilder(), catalog,
                new FakeProviderFactory(provider), noToolsFactory(), skills, "a");
    }

    private ToolRegistryFactory noToolsFactory() {
        return (config, catalog, io, taskRunner) -> new ToolRegistry(List.of());
    }

    private SkillCatalog emptySkills() {
        return SkillCatalog.discover(Path.of("/nonexistent-project-skills"),
                Path.of("/nonexistent-global-skills"));
    }

    private SkillCatalog skillsCatalog(String name, String description) throws IOException {
        Path dir = tempDir.resolve(name);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"),
                "---\nname: " + name + "\ndescription: " + description + "\n---\nbody text");
        return SkillCatalog.discover(tempDir, tempDir.resolve("nope"));
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
        final List<List<Tool>> receivedTools = new ArrayList<>();
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
            receivedTools.add(new ArrayList<>(tools));
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
        final List<String> skillLoads = new ArrayList<>();
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

        @Override
        public void appendSkillLoad(UUID sessionId, String name) throws IOException {
            appendAttempts++;
            if (failAppend) {
                throw new IOException("boom");
            }
            skillLoads.add(name);
        }
    }

    static class FakeTool implements Tool {
        final String name;
        final boolean readOnly;
        final ToolResult result;
        int calls = 0;

        FakeTool(String name, boolean readOnly, ToolResult result) {
            this.name = name;
            this.readOnly = readOnly;
            this.result = result;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return name;
        }

        @Override
        public JsonNode parametersSchema() {
            return JSON.createObjectNode();
        }

        @Override
        public boolean isReadOnly() {
            return readOnly;
        }

        @Override
        public ToolResult execute(JsonNode args) {
            calls++;
            return result;
        }
    }

    static class FakeToolProvider extends FakeProvider {
        private ToolCall firstCall;
        private boolean alwaysCall;
        private String answer;

        FakeToolProvider(ToolCall firstCall, String answer) {
            this.firstCall = firstCall;
            this.answer = answer;
        }

        FakeToolProvider() {
        }

        void alwaysCall(String name, JsonNode args) {
            this.firstCall = new ToolCall("call_x", name, args);
            this.alwaysCall = true;
        }

        @Override
        public ProviderResponse send(List<ChatMessage> history, List<Tool> tools,
                                     Consumer<String> tokenSink, Consumer<String> reasoningSink) {
            receivedHistories.add(new ArrayList<>(history));
            receivedTools.add(new ArrayList<>(tools));
            calls++;
            if (alwaysCall && calls < 10) {
                return new ProviderResponse(
                        new ChatMessage(Role.ASSISTANT, null, null, List.of(firstCall), null),
                        turnUsage, estimated);
            }
            if (firstCall != null && calls == 1) {
                return new ProviderResponse(
                        new ChatMessage(Role.ASSISTANT, null, null, List.of(firstCall), null),
                        turnUsage, estimated);
            }
            ChatMessage last = history.get(history.size() - 1);
            String reply = answer != null ? answer : last.content() + " response";
            tokenSink.accept(reply);
            if (thinking != null) {
                reasoningSink.accept(thinking);
            }
            return new ProviderResponse(new ChatMessage(Role.ASSISTANT, reply, thinking), turnUsage, estimated);
        }
    }
}
