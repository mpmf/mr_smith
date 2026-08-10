package com.mrsmith.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mrsmith.config.AgentCatalog;
import com.mrsmith.config.AgentConfig;
import com.mrsmith.config.ProviderConfig;
import com.mrsmith.io.IO;
import com.mrsmith.provider.ChatMessage;
import com.mrsmith.provider.Provider;
import com.mrsmith.provider.ProviderFactory;
import com.mrsmith.provider.ProviderResponse;
import com.mrsmith.provider.Role;
import com.mrsmith.provider.ToolCall;
import com.mrsmith.provider.Usage;
import com.mrsmith.skill.SkillCatalog;
import com.mrsmith.tool.TaskResult;
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
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubAgentRunnerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDir;

    private final UUID sessionId = UUID.randomUUID();

    private AgentCatalog catalog() {
        return new AgentCatalog(
                List.of(new ProviderConfig("p", "sk-test", "https://example.com/v1")),
                List.of(new AgentConfig("a", "p", "m", "you are a", null)),
                "a", true, tempDir);
    }

    private SkillCatalog emptySkills() {
        return SkillCatalog.discover(tempDir.resolve("nope-project"), tempDir.resolve("nope-global"));
    }

    private ToolRegistryFactory fixedRegistry(ToolRegistry tools) {
        return (config, catalog, io, taskRunner) -> tools;
    }

    private SubAgentRunner runner(Provider provider, ToolRegistry tools, IO io) throws IOException {
        Files.createDirectories(tempDir.resolve(sessionId.toString()));
        AgentCatalog catalog = catalog();
        ProviderFactory factory = new FakeProviderFactory(provider);
        UsageTracker tracker = new UsageTracker();
        return new SubAgentRunner(new SubAgentRunner.Context(catalog, factory, fixedRegistry(tools),
                emptySkills(), io, tracker, () -> catalog.resolve("a"), () -> sessionId,
                () -> new ToolBudget(null, io)));
    }

    private List<String> readSubAgentFile(int n) throws IOException {
        return Files.readAllLines(tempDir.resolve(sessionId.toString()).resolve("subagent-" + n + ".jsonl"));
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

        @Override
        public void writeToolExecution(String line) {
            lines.add(line);    
        }

        @Override
        public void writePrompt(String line) {
            lines.add(line);    
        }
    }

    static class FakeProvider implements Provider {
        final List<ToolCall> plannedCalls;
        final List<List<ChatMessage>> receivedHistories = new ArrayList<>();
        int calls = 0;

        FakeProvider(ToolCall... plannedCalls) {
            this.plannedCalls = List.of(plannedCalls);
        }

        @Override
        public ProviderResponse send(List<ChatMessage> history, List<Tool> tools, Consumer<String> tokenSink,
                                     Consumer<String> reasoningSink) {
            receivedHistories.add(new ArrayList<>(history));
            calls++;
            if (calls <= plannedCalls.size()) {
                return new ProviderResponse(
                        new ChatMessage(Role.ASSISTANT, null, null, List.of(plannedCalls.get(calls - 1)), null),
                        new Usage(10, 5), false);
            }
            ChatMessage last = history.get(history.size() - 1);
            String reply = last.content() + " sub reply";
            tokenSink.accept(reply);
            return new ProviderResponse(new ChatMessage(Role.ASSISTANT, reply), new Usage(10, 5), false);
        }
    }

    static class FakeProviderFactory implements ProviderFactory {
        final Provider provider;

        FakeProviderFactory(Provider provider) {
            this.provider = provider;
        }

        @Override
        public Provider create(com.mrsmith.config.AgentRuntime runtime) {
            return provider;
        }
    }

    static class AlwaysCallProvider implements Provider {
        final ToolCall call;
        int calls = 0;

        AlwaysCallProvider(String name, JsonNode args) {
            this.call = new ToolCall("call_x", name, args);
        }

        @Override
        public ProviderResponse send(List<ChatMessage> history, List<Tool> tools, Consumer<String> tokenSink,
                                     Consumer<String> reasoningSink) {
            calls++;
            return new ProviderResponse(
                    new ChatMessage(Role.ASSISTANT, null, null, List.of(call), null),
                    new Usage(10, 5), false);
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

    @Test
    void freshRunReturnsFinalAnswerAndWritesTranscript() throws Exception {
        FakeProvider provider = new FakeProvider();
        ToolRegistry tools = new ToolRegistry(List.of());
        SubAgentRunner runner = runner(provider, tools, new StubIo(List.of()));
        TaskResult result = runner.run("do the thing", null, null);
        assertFalse(result.error());
        assertEquals("subagent-1", result.id());
        assertTrue(result.message().contains("do the thing"));
        List<String> lines = readSubAgentFile(1);
        assertEquals(2, lines.size());
        assertTrue(lines.get(0).contains("\"type\":\"user\""));
        assertTrue(lines.get(1).contains("\"type\":\"assistant\""));
    }

    @Test
    void toolCallsAreRecordedInTranscript() throws Exception {
        FakeTool readFile = new FakeTool("read_file", true, new ToolResult("contents", false));
        ToolRegistry tools = new ToolRegistry(List.of(readFile));
        FakeProvider provider = new FakeProvider(
                new ToolCall("c1", "read_file", JSON.readTree("{\"path\":\"a.txt\"}")));
        SubAgentRunner runner = runner(provider, tools, new StubIo(List.of()));
        TaskResult result = runner.run("inspect", null, null);
        assertFalse(result.error());
        assertEquals(1, readFile.calls);
        List<String> lines = readSubAgentFile(1);
        assertTrue(lines.stream().anyMatch(l -> l.contains("\"type\":\"tool_call\"")));
        assertTrue(lines.stream().anyMatch(l -> l.contains("\"type\":\"tool_result\"")));
    }

    @Test
    void resumeReplaysPriorContextAndAppends() throws Exception {
        FakeProvider provider = new FakeProvider();
        ToolRegistry tools = new ToolRegistry(List.of());
        SubAgentRunner runner = runner(provider, tools, new StubIo(List.of()));
        runner.run("first", null, null);
        assertEquals(1, provider.receivedHistories.size());
        TaskResult resumed = runner.run("continue", null, "subagent-1");
        assertFalse(resumed.error());
        assertEquals("subagent-1", resumed.id());
        assertEquals(2, provider.receivedHistories.size());
        List<ChatMessage> secondContext = provider.receivedHistories.get(1);
        assertTrue(secondContext.stream().anyMatch(m -> m.role() == Role.SYSTEM && m.content().contains("you are a")));
        assertTrue(secondContext.stream().anyMatch(m -> m.role() == Role.USER && m.content().equals("first")));
        assertTrue(secondContext.stream().anyMatch(m -> m.role() == Role.USER && m.content().equals("continue")));
        assertEquals(4, readSubAgentFile(1).size());
    }

    @Test
    void unknownAgentReturnsError() throws Exception {
        SubAgentRunner runner = runner(new FakeProvider(), new ToolRegistry(List.of()), new StubIo(List.of()));
        TaskResult result = runner.run("x", "nope", null);
        assertTrue(result.error());
        assertTrue(result.message().contains("Unknown agent"));
    }

    @Test
    void unknownTaskIdReturnsError() throws Exception {
        SubAgentRunner runner = runner(new FakeProvider(), new ToolRegistry(List.of()), new StubIo(List.of()));
        TaskResult result = runner.run("x", null, "subagent-9");
        assertTrue(result.error());
        assertTrue(result.message().contains("Unknown task_id"));
    }

    @Test
    void resetRestartsSequentialNumbering() throws Exception {
        SubAgentRunner runner = runner(new FakeProvider(), new ToolRegistry(List.of()), new StubIo(List.of()));
        assertEquals("subagent-1", runner.run("a", null, null).id());
        assertEquals("subagent-2", runner.run("b", null, null).id());
        runner.reset();
        assertEquals("subagent-1", runner.run("c", null, null).id());
    }

    @Test
    void destructiveToolPromptsForApproval() throws Exception {
        FakeTool edit = new FakeTool("edit", false, new ToolResult("edited", false));
        ToolRegistry tools = new ToolRegistry(List.of(edit));
        FakeProvider provider = new FakeProvider(
                new ToolCall("c1", "edit", JSON.readTree("{\"filePath\":\"a.txt\",\"oldString\":\"x\",\"newString\":\"y\"}")));
        SubAgentRunner runner = runner(provider, tools, new StubIo(List.of("y")));
        TaskResult result = runner.run("edit it", null, null);
        assertFalse(result.error());
        assertEquals(1, edit.calls);
        List<String> lines = readSubAgentFile(1);
        assertTrue(lines.stream().anyMatch(l -> l.contains("\"type\":\"tool_result\"")));
    }

    @Test
    void declinedDestructiveToolRecordsDecline() throws Exception {
        FakeTool edit = new FakeTool("edit", false, new ToolResult("edited", false));
        ToolRegistry tools = new ToolRegistry(List.of(edit));
        FakeProvider provider = new FakeProvider(
                new ToolCall("c1", "edit", JSON.readTree("{\"filePath\":\"a.txt\",\"oldString\":\"x\",\"newString\":\"y\"}")));
        SubAgentRunner runner = runner(provider, tools, new StubIo(List.of("n")));
        TaskResult result = runner.run("edit it", null, null);
        assertFalse(result.error());
        assertEquals(0, edit.calls);
        List<String> lines = readSubAgentFile(1);
        assertTrue(lines.stream().anyMatch(l -> l.contains("declined")));
    }

    @Test
    void subAgentUsageAccumulatesInSessionTracker() throws Exception {
        UsageTracker tracker = new UsageTracker();
        Files.createDirectories(tempDir.resolve(sessionId.toString()));
        AgentCatalog catalog = catalog();
        SubAgentRunner runner = new SubAgentRunner(new SubAgentRunner.Context(catalog,
                new FakeProviderFactory(new FakeProvider()), fixedRegistry(new ToolRegistry(List.of())),
                emptySkills(), new StubIo(List.of()), tracker,
                () -> catalog.resolve("a"), () -> sessionId,
                () -> new ToolBudget(null, new StubIo(List.of()))));
        runner.run("x", null, null);
        assertEquals(10, tracker.promptTokens());
        assertEquals(5, tracker.completionTokens());
        assertEquals(15, tracker.totalTokens());
    }

    @Test
    void runsWithoutTranscriptWhenSessionIdIsNull() throws Exception {
        AgentCatalog catalog = catalog();
        SubAgentRunner runner = new SubAgentRunner(new SubAgentRunner.Context(catalog,
                new FakeProviderFactory(new FakeProvider()), fixedRegistry(new ToolRegistry(List.of())),
                emptySkills(), new StubIo(List.of()), new UsageTracker(),
                () -> catalog.resolve("a"), () -> null,
                () -> new ToolBudget(null, new StubIo(List.of()))));
        TaskResult result = runner.run("x", null, null);
        assertFalse(result.error());
        assertEquals("subagent-1", result.id());
    }

    @Test
    void subAgentCallsCountAgainstSharedBudget() throws Exception {
        FakeTool readFile = new FakeTool("read_file", true, new ToolResult("contents", false));
        ToolRegistry tools = new ToolRegistry(List.of(readFile));
        AlwaysCallProvider provider = new AlwaysCallProvider("read_file", JSON.readTree("{\"path\":\"a.txt\"}"));
        StubIo io = new StubIo(List.of());
        ToolBudget budget = new ToolBudget(3, io);
        Files.createDirectories(tempDir.resolve(sessionId.toString()));
        AgentCatalog catalog = catalog();
        SubAgentRunner runner = new SubAgentRunner(new SubAgentRunner.Context(catalog,
                new FakeProviderFactory(provider), fixedRegistry(tools),
                emptySkills(), io, new UsageTracker(), () -> catalog.resolve("a"), () -> sessionId,
                () -> budget));
        TaskResult result = runner.run("do it", null, null);
        assertFalse(result.error());
        assertEquals(3, readFile.calls);
        assertEquals(3, budget.used());
        assertTrue(result.message() == null || !result.message().contains("round limit"));
    }

    @Test
    void subAgentContinuesToolRoundsWhenUserExtends() throws Exception {
        AgentCatalog catalog = new AgentCatalog(
                List.of(new ProviderConfig("p", "sk-test", "https://example.com/v1")),
                List.of(new AgentConfig("a", "p", "m", null, null, 2)),
                "a", true, tempDir);
        FakeTool readFile = new FakeTool("read_file", true, new ToolResult("contents", false));
        ToolRegistry tools = new ToolRegistry(List.of(readFile));
        FakeProvider provider = new FakeProvider(
                new ToolCall("c1", "read_file", JSON.readTree("{}")),
                new ToolCall("c2", "read_file", JSON.readTree("{}")),
                new ToolCall("c3", "read_file", JSON.readTree("{}")),
                new ToolCall("c4", "read_file", JSON.readTree("{}")),
                new ToolCall("c5", "read_file", JSON.readTree("{}")),
                new ToolCall("c6", "read_file", JSON.readTree("{}")));
        StubIo io = new StubIo(List.of("y", "n"));
        Files.createDirectories(tempDir.resolve(sessionId.toString()));
        SubAgentRunner runner = new SubAgentRunner(new SubAgentRunner.Context(catalog,
                new FakeProviderFactory(provider), fixedRegistry(tools),
                emptySkills(), io, new UsageTracker(), () -> catalog.resolve("a"), () -> sessionId,
                () -> new ToolBudget(null, io)));
        TaskResult result = runner.run("do it", null, null);
        assertFalse(result.error());
        assertEquals(5, readFile.calls);
    }
}
