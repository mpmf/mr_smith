package com.mrsmith.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.mrsmith.config.AgentCatalog;
import com.mrsmith.config.AppConfig;
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
import com.mrsmith.skill.Skill;
import com.mrsmith.skill.SkillCatalog;
import com.mrsmith.tool.SkillTool;
import com.mrsmith.tool.TodowriteTool;
import com.mrsmith.tool.Tool;
import com.mrsmith.tool.ToolException;
import com.mrsmith.tool.ToolRegistry;
import com.mrsmith.tool.ToolRegistryFactory;
import com.mrsmith.tool.ToolResult;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

public class ChatSession {

    private static final int WARN_THRESHOLD_PERCENT = 85;
    private static final int LIMIT_PERCENT = 100;
    private static final int MAX_TOOL_ROUNDS = 8;

    private final IO io;
    private final TranscriptWriter transcripts;
    private final ContextBuilder contextBuilder;
    private final AgentCatalog agents;
    private final ProviderFactory providerFactory;
    private final ToolRegistryFactory toolRegistryFactory;
    private final SkillCatalog skills;
    private final String initialAgentName;

    private final List<ChatMessage> history = new ArrayList<>();
    private final UsageTracker tracker = new UsageTracker();
    private boolean warned85;
    private boolean warned100;
    private UUID currentSessionId;
    private String currentAgentName;
    private AppConfig config;
    private Provider provider;
    private ToolRegistry toolRegistry;

    public ChatSession(IO io, TranscriptWriter transcripts, ContextBuilder contextBuilder,
                       AgentCatalog agents, ProviderFactory providerFactory,
                       ToolRegistryFactory toolRegistryFactory, SkillCatalog skills,
                       String initialAgentName) {
        this.io = io;
        this.transcripts = transcripts;
        this.contextBuilder = contextBuilder;
        this.agents = agents;
        this.providerFactory = providerFactory;
        this.toolRegistryFactory = toolRegistryFactory;
        this.skills = skills;
        this.initialAgentName = initialAgentName;
    }

    public void run() throws IOException {
        io.writeLine("Mr Smith. Type /help for commands, /exit to quit.");
        currentAgentName = initialAgentName;
        applyAgent();
        io.writeLine("Agent: " + currentAgentName);
        startFreshSession();
        String line;
        while ((line = io.readLine()) != null) {
            if (line.equals("/exit")) {
                break;
            }
            if (handleCommand(line)) {
                continue;
            }
            history.add(new ChatMessage(Role.USER, line));
            appendUser(line);
            contextBuilder.appendUser(line);
            try {
                TurnResult turn = runToolLoop();
                history.add(turn.message());
                contextBuilder.appendAssistant(turn.message().content());
                appendAssistant(turn.message().content(), turn.message().thinking(),
                        turn.usage(), turn.estimated());
                io.writeLine("");
                tracker.recordTurn(turn.usage(), turn.estimated());
                String usageLine = tracker.lastTurnLine();
                if (!usageLine.isEmpty()) {
                    io.writeLine(usageLine);
                }
                warnIfNearLimit();
            } catch (ProviderException e) {
                if (e.hasPartialContent() || e.partialThinking() != null) {
                    history.add(new ChatMessage(Role.ASSISTANT, e.partialContent(), e.partialThinking()));
                    contextBuilder.appendAssistant(e.partialContent());
                    appendAssistant(e.partialContent(), e.partialThinking(), null, false);
                }
                io.writeLine("");
                io.writeLine("Error: " + e.getMessage());
            } catch (RuntimeException e) {
                io.writeLine("");
                io.writeLine("Error: " + e.getMessage());
            }
        }
    }

    private TurnResult runToolLoop() {
        Accumulator acc = new Accumulator();
        for (int round = 0; ; round++) {
            List<ChatMessage> context = contextBuilder.messages();
            ProviderResponse response = provider.send(context, toolRegistry.tools(),
                    io::write, io::writeReasoning);
            recordSend(response, acc);
            ChatMessage message = response.message();
            List<ToolCall> calls = message.toolCalls();
            if (calls == null || calls.isEmpty()) {
                return new TurnResult(message, new Usage(acc.prompt, acc.completion), acc.estimated);
            }
            recordToolCallMessage(message, calls);
            if (round >= MAX_TOOL_ROUNDS) {
                String limitContent = "Tool round limit (" + MAX_TOOL_ROUNDS + ") reached; answer without more tool calls.";
                for (ToolCall call : calls) {
                    appendToolResultMessage(call.id(), limitContent, false);
                }
                response = provider.send(contextBuilder.messages(), toolRegistry.tools(),
                        io::write, io::writeReasoning);
                recordSend(response, acc);
                return new TurnResult(response.message(), new Usage(acc.prompt, acc.completion), acc.estimated);
            }
            for (ToolCall call : calls) {
                ToolResult result = executeTool(call);
                io.writeLine(statusLine(call, result));
                appendToolResultMessage(call.id(), result.content(), result.error());
            }
        }
    }

    private void recordSend(ProviderResponse response, Accumulator acc) {
        acc.prompt += tokens(response.usage().promptTokens());
        acc.completion += tokens(response.usage().completionTokens());
        acc.estimated = acc.estimated || response.usageEstimated();
    }

    private int tokens(Integer value) {
        return value == null ? 0 : value;
    }

    private void recordToolCallMessage(ChatMessage message, List<ToolCall> calls) {
        history.add(message);
        contextBuilder.appendAssistantToolCalls(calls);
        for (ToolCall call : calls) {
            appendToolCall(call);
        }
    }

    private void appendToolResultMessage(String id, String content, boolean error) {
        ChatMessage message = new ChatMessage(Role.TOOL, content, null, null, id);
        history.add(message);
        contextBuilder.appendToolResult(id, content);
        appendToolResult(id, content, error);
    }

    private ToolResult executeTool(ToolCall call) {
        Optional<Tool> found = toolRegistry.find(call.name());
        if (found.isEmpty()) {
            return new ToolResult("Unknown tool: " + call.name(), true);
        }
        Tool tool = found.get();
        if (!tool.isReadOnly() && !confirm(call, tool)) {
            return new ToolResult("User declined to run " + call.name() + ".", true);
        }
        try {
            return tool.execute(call.arguments());
        } catch (ToolException e) {
            return new ToolResult(e.getMessage(), true);
        }
    }

    private boolean confirm(ToolCall call, Tool tool) {
        io.write("Run " + tool.name() + "(" + describe(call) + ") [y/N]? ");
        String answer;
        try {
            answer = io.readLine();
        } catch (IOException e) {
            return false;
        }
        return answer != null && (answer.trim().equalsIgnoreCase("y")
                || answer.trim().equalsIgnoreCase("yes"));
    }

    private String statusLine(ToolCall call, ToolResult result) {
        return "tool: " + call.name() + "(" + describe(call) + ") -> "
                + (result.error() ? "error" : "ok");
    }

    private String describe(ToolCall call) {
        JsonNode args = call.arguments();
        for (String key : List.of("command", "path", "filePath", "pattern", "url")) {
            JsonNode value = args != null ? args.get(key) : null;
            if (value != null && value.isTextual()) {
                return value.asText();
            }
        }
        return "";
    }

    private void applyAgent() {
        config = agents.resolve(currentAgentName);
        provider = providerFactory.create(config);
        toolRegistry = toolRegistryFactory.create(config, skills, io);
    }

    private void startFreshSession() {
        history.clear();
        tracker.reset();
        warned85 = false;
        warned100 = false;
        contextBuilder.start(composeSystemPrompt(config.systemPrompt()));
        toolRegistry.resetSession();
        startNewSession();
    }

    private String composeSystemPrompt(String base) {
        if (skills.isEmpty()) {
            return base;
        }
        String index = skills.indexText();
        if (base == null || base.isBlank()) {
            return index;
        }
        return base + "\n\n" + index;
    }

    private void startNewSession() {
        UUID id = UuidV7.random();
        try {
            transcripts.start(id);
            currentSessionId = id;
        } catch (IOException e) {
            currentSessionId = null;
            System.err.println("Warning: could not create session folder for " + id
                    + ": " + e.getMessage() + ". Session transcript disabled.");
            return;
        }
        io.writeLine("Session: " + id);
    }

    private void switchAgent(String name) {
        if (!agents.agentNames().contains(name)) {
            io.writeLine("Unknown agent: " + name);
            return;
        }
        currentAgentName = name;
        applyAgent();
        io.writeLine("Agent: " + name);
        startFreshSession();
    }

    private void listSkills() {
        if (skills.isEmpty()) {
            io.writeLine("No skills found.");
            return;
        }
        SkillTool skillTool = skillTool();
        StringBuilder report = new StringBuilder("Skills:");
        for (String name : skills.names()) {
            Skill skill = skills.find(name).orElseThrow();
            String marker = skillTool != null && skillTool.isLoaded(name) ? "*" : "";
            report.append("\n  ").append(name).append(marker).append("  ").append(skill.description());
        }
        io.writeLine(report.toString());
    }

    private void loadSkill(String name) {
        if (skills.find(name).isEmpty()) {
            io.writeLine("Unknown skill: " + name);
            return;
        }
        SkillTool skillTool = skillTool();
        if (skillTool == null) {
            io.writeLine("Skill tool is not available.");
            return;
        }
        if (!skillTool.load(name)) {
            io.writeLine("Skill '" + name + "' is already loaded.");
            return;
        }
        String content = skills.render(name);
        history.add(new ChatMessage(Role.SYSTEM, content));
        contextBuilder.appendSystem(content);
        appendSkillLoad(name);
        io.writeLine("Loaded skill: " + name);
    }

    private SkillTool skillTool() {
        Optional<Tool> tool = toolRegistry.find("skill");
        if (tool.isPresent() && tool.get() instanceof SkillTool skillTool) {
            return skillTool;
        }
        return null;
    }

    private void listTasks() {
        TodowriteTool todoTool = todoTool();
        if (todoTool == null) {
            io.writeLine("No task list available.");
            return;
        }
        List<TodowriteTool.Task> tasks = todoTool.tasks();
        if (tasks.isEmpty()) {
            io.writeLine("No tasks.");
            return;
        }
        StringBuilder report = new StringBuilder("Tasks:");
        for (TodowriteTool.Task task : tasks) {
            report.append("\n  ").append(task.status()).append(" ")
                    .append(task.priority()).append("  ").append(task.content());
        }
        io.writeLine(report.toString());
    }

    private TodowriteTool todoTool() {
        Optional<Tool> tool = toolRegistry.find("todowrite");
        if (tool.isPresent() && tool.get() instanceof TodowriteTool todoTool) {
            return todoTool;
        }
        return null;
    }

    private void appendUser(String content) {
        if (currentSessionId == null) {
            return;
        }
        try {
            transcripts.appendUser(currentSessionId, content);
        } catch (IOException e) {
            System.err.println("Warning: could not write session transcript: " + e.getMessage());
            currentSessionId = null;
        }
    }

    private void appendAssistant(String content, String thinking, Usage usage, boolean estimated) {
        if (currentSessionId == null) {
            return;
        }
        try {
            transcripts.appendAssistant(currentSessionId, content, thinking, usage, estimated);
        } catch (IOException e) {
            System.err.println("Warning: could not write session transcript: " + e.getMessage());
            currentSessionId = null;
        }
    }

    private void appendToolCall(ToolCall call) {
        if (currentSessionId == null) {
            return;
        }
        try {
            transcripts.appendToolCall(currentSessionId, call.id(), call.name(), call.arguments());
        } catch (IOException e) {
            System.err.println("Warning: could not write session transcript: " + e.getMessage());
            currentSessionId = null;
        }
    }

    private void appendToolResult(String id, String content, boolean error) {
        if (currentSessionId == null) {
            return;
        }
        try {
            transcripts.appendToolResult(currentSessionId, id, content, error);
        } catch (IOException e) {
            System.err.println("Warning: could not write session transcript: " + e.getMessage());
            currentSessionId = null;
        }
    }

    private void appendSkillLoad(String name) {
        if (currentSessionId == null) {
            return;
        }
        try {
            transcripts.appendSkillLoad(currentSessionId, name);
        } catch (IOException e) {
            System.err.println("Warning: could not write session transcript: " + e.getMessage());
            currentSessionId = null;
        }
    }

    private void warnIfNearLimit() {
        if (!contextLimitConfigured()) {
            return;
        }
        int pct = pctOfMax();
        if (pct >= LIMIT_PERCENT) {
            if (!warned100) {
                warned100 = true;
                io.writeLine(String.format(Locale.US,
                        "Warning: session reached 100%% of your configured %,d-token context limit — consider /reset",
                        config.maxContextTokens()));
            }
        } else if (pct >= WARN_THRESHOLD_PERCENT) {
            if (!warned85) {
                warned85 = true;
                io.writeLine(String.format(Locale.US,
                        "Warning: session at %d%% of your configured %,d-token context limit — consider /reset",
                        pct, config.maxContextTokens()));
            }
        }
    }

    private boolean contextLimitConfigured() {
        Integer maxContext = config.maxContextTokens();
        return maxContext != null && maxContext > 0;
    }

    private int pctOfMax() {
        return (int) Math.round(tracker.totalTokens() * 100.0 / config.maxContextTokens());
    }

    private boolean handleCommand(String line) {
        if (!line.startsWith("/")) {
            return false;
        }
        if (line.startsWith("/agent ")) {
            switchAgent(line.substring("/agent ".length()).trim());
            return true;
        }
        if (line.equals("/skills")) {
            listSkills();
            return true;
        }
        if (line.startsWith("/skills ")) {
            loadSkill(line.substring("/skills ".length()).trim());
            return true;
        }
        if (line.equals("/tasks")) {
            listTasks();
            return true;
        }
        switch (line) {
            case "/reset" -> {
                startFreshSession();
                io.writeLine("History cleared.");
            }
            case "/agents" -> io.writeLine("Agents: " + String.join(", ", agents.agentNames()));
            case "/usage" -> io.writeLine(usageReport());
            case "/help" -> io.writeLine("Commands: /exit, /reset, /help, /usage, /agents, /agent <name>, /skills [name], /tasks. Anything else is sent to the LLM.");
            default -> io.writeLine("Unknown command: " + line + " (type /help)");
        }
        return true;
    }

    private String usageReport() {
        StringBuilder report = new StringBuilder(tracker.usageReport());
        if (contextLimitConfigured()) {
            report.append(String.format(Locale.US, "%n  context limit: %,d configured (%d%% used)",
                    config.maxContextTokens(), pctOfMax()));
        }
        report.append(String.format(Locale.US, "%n  history: %d messages", history.size()));
        return report.toString();
    }

    private record TurnResult(ChatMessage message, Usage usage, boolean estimated) {
    }

    private static final class Accumulator {
        int prompt;
        int completion;
        boolean estimated;
    }
}
