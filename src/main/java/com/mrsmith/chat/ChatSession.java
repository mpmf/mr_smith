package com.mrsmith.chat;

import com.mrsmith.config.AgentCatalog;
import com.mrsmith.config.AgentRuntime;
import com.mrsmith.io.IO;
import com.mrsmith.provider.ChatMessage;
import com.mrsmith.provider.Provider;
import com.mrsmith.provider.ProviderException;
import com.mrsmith.provider.ProviderFactory;
import com.mrsmith.provider.Role;
import com.mrsmith.provider.ToolCall;
import com.mrsmith.provider.Usage;
import com.mrsmith.session.TranscriptWriter;
import com.mrsmith.skill.Skill;
import com.mrsmith.skill.SkillCatalog;
import com.mrsmith.tool.SkillTool;
import com.mrsmith.tool.TodowriteTool;
import com.mrsmith.tool.ToolRegistry;
import com.mrsmith.tool.ToolRegistryFactory;
import com.mrsmith.tool.ToolState;
import com.mrsmith.util.Warn;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

public class ChatSession {

    private static final int WARN_THRESHOLD_PERCENT = 85;
    private static final int LIMIT_PERCENT = 100;

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
    private AgentRuntime runtime;
    private Provider provider;
    private ToolRegistry toolRegistry;
    private ToolState toolState;
    private SubAgentRunner subAgentRunner;
    private ToolBudget toolBudget;
    private ToolApproval toolApproval = new ToolApproval();

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
        ToolLoop.LoopResult result = ToolLoop.run(contextBuilder, provider, toolRegistry.tools(),
                io, maxToolRounds(), toolBudget, new ToolLoop.Sink() {
                    @Override
                    public void assistantWithToolCalls(ChatMessage message, List<ToolCall> calls) {
                        history.add(message);
                        contextBuilder.appendAssistantToolCalls(calls);
                        for (ToolCall call : calls) {
                            appendToolCall(call);
                        }
                    }

                    @Override
                    public void toolResult(String id, String content, boolean error) {
                        history.add(new ChatMessage(Role.TOOL, content, null, null, id));
                        contextBuilder.appendToolResult(id, content);
                        appendToolResult(id, content, error);
                    }
                }, toolApproval);
        return new TurnResult(result.message(), result.usage(), result.estimated());
    }

    private int maxToolRounds() {
        Integer value = runtime.agent().maxToolRounds();
        return value == null ? ToolLoop.DEFAULT_MAX_TOOL_ROUNDS : value;
    }

    private void applyAgent() {
        runtime = agents.resolve(currentAgentName);
        provider = providerFactory.create(runtime);
        subAgentRunner = new SubAgentRunner(new SubAgentRunner.Context(
                agents, providerFactory, toolRegistryFactory, skills, io, tracker,
                () -> runtime, () -> currentSessionId, () -> toolBudget, () -> toolApproval));
        toolRegistry = toolRegistryFactory.create(runtime, skills, io, subAgentRunner);
        toolState = toolRegistry;
    }

    private void startFreshSession() {
        history.clear();
        tracker.reset();
        warned85 = false;
        warned100 = false;
        toolBudget = new ToolBudget(runtime.agent().maxToolCallsPerSession(), io);
        toolApproval.reset();
        contextBuilder.start(composeSystemPrompt(runtime.agent().systemPrompt()));
        toolRegistry.resetSession();
        subAgentRunner.reset();
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
            Warn.warn("could not create session folder for " + id
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
        Set<String> loaded = toolState.loadedSkills();
        StringBuilder report = new StringBuilder("Skills:");
        for (String name : skills.names()) {
            Skill skill = skills.find(name).orElseThrow();
            String marker = loaded.contains(name) ? "*" : "";
            report.append("\n  ").append(name).append(marker).append("  ").append(skill.description());
        }
        io.writeLine(report.toString());
    }

    private void loadSkill(String name) {
        SkillTool.SkillLoad result = toolState.loadSkill(name);
        if (result.error() || !result.loaded()) {
            io.writeLine(result.message());
            return;
        }
        String content = result.content();
        history.add(new ChatMessage(Role.SYSTEM, content));
        contextBuilder.appendSystem(content);
        appendSkillLoad(name);
        io.writeLine("Loaded skill: " + name);
    }

    private void listTasks() {
        List<TodowriteTool.Task> tasks = toolState.tasks();
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

    private void appendUser(String content) {
        if (currentSessionId == null) {
            return;
        }
        try {
            transcripts.appendUser(currentSessionId, content);
        } catch (IOException e) {
            Warn.warn("could not write session transcript: " + e.getMessage());
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
            Warn.warn("could not write session transcript: " + e.getMessage());
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
            Warn.warn("could not write session transcript: " + e.getMessage());
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
            Warn.warn("could not write session transcript: " + e.getMessage());
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
            Warn.warn("could not write session transcript: " + e.getMessage());
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
                        runtime.agent().maxContextTokens()));
            }
        } else if (pct >= WARN_THRESHOLD_PERCENT) {
            if (!warned85) {
                warned85 = true;
                io.writeLine(String.format(Locale.US,
                        "Warning: session at %d%% of your configured %,d-token context limit — consider /reset",
                        pct, runtime.agent().maxContextTokens()));
            }
        }
    }

    private boolean contextLimitConfigured() {
        Integer maxContext = runtime.agent().maxContextTokens();
        return maxContext != null && maxContext > 0;
    }

    private int pctOfMax() {
        return (int) Math.round(tracker.totalTokens() * 100.0 / runtime.agent().maxContextTokens());
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
                    runtime.agent().maxContextTokens(), pctOfMax()));
        }
        report.append(String.format(Locale.US, "%n  history: %d messages", history.size()));
        if (toolBudget != null && !toolBudget.isUnlimited()) {
            report.append(String.format(Locale.US, "%n  tool calls: %d/%d", toolBudget.used(), toolBudget.limit()));
        }
        return report.toString();
    }

    private record TurnResult(ChatMessage message, Usage usage, boolean estimated) {
    }
}
