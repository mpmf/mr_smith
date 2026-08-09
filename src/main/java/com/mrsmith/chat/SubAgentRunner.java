package com.mrsmith.chat;

import com.mrsmith.config.AgentCatalog;
import com.mrsmith.config.AppConfig;
import com.mrsmith.config.ConfigException;
import com.mrsmith.io.IO;
import com.mrsmith.provider.ChatMessage;
import com.mrsmith.provider.Provider;
import com.mrsmith.provider.ProviderFactory;
import com.mrsmith.provider.ToolCall;
import com.mrsmith.session.SubAgentTranscriptStore;
import com.mrsmith.session.TranscriptWriter;
import com.mrsmith.tool.Resettable;
import com.mrsmith.tool.TaskResult;
import com.mrsmith.tool.TaskRunner;
import com.mrsmith.tool.ToolRegistry;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

public final class SubAgentRunner implements TaskRunner, Resettable {

    private final AgentCatalog agents;
    private final ProviderFactory providerFactory;
    private final Function<AppConfig, ToolRegistry> toolsBuilder;
    private final IO io;
    private final UsageTracker tracker;
    private final Supplier<AppConfig> currentConfig;
    private final Supplier<UUID> sessionId;
    private final SubAgentTranscriptStore store;

    private int counter;

    public SubAgentRunner(AgentCatalog agents, ProviderFactory providerFactory,
                          Function<AppConfig, ToolRegistry> toolsBuilder, IO io,
                          UsageTracker tracker, Supplier<AppConfig> currentConfig,
                          Supplier<UUID> sessionId) {
        this.agents = agents;
        this.providerFactory = providerFactory;
        this.toolsBuilder = toolsBuilder;
        this.io = io;
        this.tracker = tracker;
        this.currentConfig = currentConfig;
        this.sessionId = sessionId;
        this.store = new SubAgentTranscriptStore(agents.sessionsDir(), sessionId);
    }

    @Override
    public void reset() {
        counter = 0;
    }

    @Override
    public TaskResult run(String prompt, String agentName, String taskId) {
        AppConfig config = resolveConfig(agentName);
        if (config == null) {
            return new TaskResult(null, "Unknown agent: " + agentName, true);
        }
        int n;
        boolean resume;
        if (taskId == null || taskId.isBlank()) {
            n = ++counter;
            resume = false;
        } else {
            Integer parsed = parseTaskId(taskId);
            if (parsed == null) {
                return new TaskResult(null, "Unknown task_id: " + taskId, true);
            }
            n = parsed;
            resume = true;
        }
        FullContextBuilder context = new FullContextBuilder();
        context.start(config.systemPrompt());
        List<ChatMessage> replayed;
        try {
            replayed = resume ? store.read(n) : List.of();
        } catch (IOException e) {
            return new TaskResult(null, "Unknown task_id: " + taskId, true);
        }
        if (replayed == null) {
            return new TaskResult(null, "Unknown task_id: " + taskId, true);
        }
        for (ChatMessage message : replayed) {
            replay(context, message);
        }
        context.appendUser(prompt);

        Provider provider = providerFactory.create(config);
        ToolRegistry tools = toolsBuilder.apply(config);
        TranscriptWriter transcripts = store.writer(n);
        try {
            if (sessionId.get() != null) {
                transcripts.start(sessionId.get());
                transcripts.appendUser(sessionId.get(), prompt);
            }
            ToolLoop.LoopResult result = ToolLoop.run(context, provider, tools.tools(),
                    io, maxToolRounds(config), sinkFor(context, transcripts));
            if (sessionId.get() != null) {
                transcripts.appendAssistant(sessionId.get(), result.message().content(),
                        result.message().thinking(), result.usage(), result.estimated());
            }
            tracker.recordSessionUsage(result.usage(), result.estimated());
            return new TaskResult("subagent-" + n, result.message().content(), false);
        } catch (RuntimeException e) {
            return new TaskResult("subagent-" + n, e.getMessage(), true);
        } catch (IOException e) {
            return new TaskResult("subagent-" + n, "could not write subagent transcript: " + e.getMessage(), true);
        }
    }

    private AppConfig resolveConfig(String agentName) {
        if (agentName == null || agentName.isBlank()) {
            return currentConfig.get();
        }
        try {
            return agents.resolve(agentName);
        } catch (ConfigException e) {
            return null;
        }
    }

    private ToolLoop.Sink sinkFor(FullContextBuilder context, TranscriptWriter transcripts) {
        return new ToolLoop.Sink() {
            @Override
            public void assistantWithToolCalls(ChatMessage message, List<ToolCall> calls) {
                context.appendAssistantToolCalls(calls);
                if (sessionId.get() != null) {
                    try {
                        for (ToolCall call : calls) {
                            transcripts.appendToolCall(sessionId.get(), call.id(), call.name(), call.arguments());
                        }
                    } catch (IOException e) {
                        System.err.println("Warning: could not write subagent transcript: " + e.getMessage());
                    }
                }
            }

            @Override
            public void toolResult(String id, String content, boolean error) {
                context.appendToolResult(id, content);
                if (sessionId.get() != null) {
                    try {
                        transcripts.appendToolResult(sessionId.get(), id, content, error);
                    } catch (IOException e) {
                        System.err.println("Warning: could not write subagent transcript: " + e.getMessage());
                    }
                }
            }
        };
    }

    private static void replay(FullContextBuilder context, ChatMessage message) {
        switch (message.role()) {
            case SYSTEM -> context.appendSystem(message.content());
            case USER -> context.appendUser(message.content());
            case ASSISTANT -> {
                if (message.toolCalls() != null && !message.toolCalls().isEmpty()) {
                    context.appendAssistantToolCalls(message.toolCalls());
                } else {
                    context.appendAssistant(message.content());
                }
            }
            case TOOL -> context.appendToolResult(message.toolCallId(), message.content());
        }
    }

    private static Integer parseTaskId(String taskId) {
        if (!taskId.startsWith("subagent-")) {
            return null;
        }
        String rest = taskId.substring("subagent-".length());
        if (!rest.matches("\\d{1,9}")) {
            return null;
        }
        return Integer.parseInt(rest);
    }

    private static int maxToolRounds(AppConfig config) {
        Integer value = config.maxToolRounds();
        return value == null ? ToolLoop.DEFAULT_MAX_TOOL_ROUNDS : value;
    }
}
