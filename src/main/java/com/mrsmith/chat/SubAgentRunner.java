package com.mrsmith.chat;

import com.mrsmith.config.AgentCatalog;
import com.mrsmith.config.AgentRuntime;
import com.mrsmith.config.ConfigException;
import com.mrsmith.io.IO;
import com.mrsmith.provider.ChatMessage;
import com.mrsmith.provider.Provider;
import com.mrsmith.provider.ProviderFactory;
import com.mrsmith.provider.ToolCall;
import com.mrsmith.session.SubAgentTranscriptStore;
import com.mrsmith.session.TranscriptWriter;
import com.mrsmith.skill.SkillCatalog;
import com.mrsmith.tool.Resettable;
import com.mrsmith.tool.TaskResult;
import com.mrsmith.tool.TaskRunner;
import com.mrsmith.tool.ToolRegistry;
import com.mrsmith.tool.ToolRegistryFactory;
import com.mrsmith.util.Warn;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

public final class SubAgentRunner implements TaskRunner, Resettable {

    public record Context(AgentCatalog agents, ProviderFactory providerFactory,
                          ToolRegistryFactory toolRegistryFactory, SkillCatalog skills,
                          IO io, UsageTracker tracker,
                          Supplier<AgentRuntime> currentConfig,
                          Supplier<UUID> sessionId, Supplier<ToolBudget> budget,
                          Supplier<ToolApproval> approval) {
    }

    private final AgentCatalog agents;
    private final ProviderFactory providerFactory;
    private final ToolRegistryFactory toolRegistryFactory;
    private final SkillCatalog skills;
    private final IO io;
    private final UsageTracker tracker;
    private final Supplier<AgentRuntime> currentConfig;
    private final Supplier<UUID> sessionId;
    private final Supplier<ToolBudget> budget;
    private final Supplier<ToolApproval> approval;
    private final SubAgentTranscriptStore store;

    private int counter;

    public SubAgentRunner(Context context) {
        this.agents = context.agents();
        this.providerFactory = context.providerFactory();
        this.toolRegistryFactory = context.toolRegistryFactory();
        this.skills = context.skills();
        this.io = context.io();
        this.tracker = context.tracker();
        this.currentConfig = context.currentConfig();
        this.sessionId = context.sessionId();
        this.budget = context.budget();
        this.approval = context.approval();
        this.store = new SubAgentTranscriptStore(agents.sessionsDir(), sessionId);
    }

    @Override
    public void reset() {
        counter = 0;
    }

    @Override
    public TaskResult run(String prompt, String agentName, String taskId) {
        UUID sid = sessionId.get();
        AgentRuntime config = resolveConfig(agentName);
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
        if (resume && sid == null) {
            return new TaskResult(null, "Unknown task_id: " + taskId, true);
        }
        ContextBuilder context = ContextBuilders.create(config);
        context.start(config.agent().systemPrompt(), ContextBuilders.windowBudget(config));
        List<ChatMessage> replayed = List.of();
        if (resume) {
            try {
                replayed = store.read(n);
            } catch (IOException e) {
                return new TaskResult(null, "Unknown task_id: " + taskId, true);
            }
            if (replayed == null) {
                return new TaskResult(null, "Unknown task_id: " + taskId, true);
            }
        }
        for (ChatMessage message : replayed) {
            replay(context, message);
        }
        context.appendUser(prompt);

        Provider provider = providerFactory.create(config);
        ToolRegistry tools = toolRegistryFactory.create(config, skills, io, null);
        TranscriptWriter transcripts = sid == null ? null : store.writer(n);
        try {
            if (transcripts != null) {
                transcripts.start(sid);
                transcripts.appendUser(sid, prompt);
            }
            ToolLoop.LoopResult result = ToolLoop.run(context, provider, tools.tools(),
                    io, maxToolRounds(config), budget.get(), sinkFor(context, transcripts),
                    approval.get());
            if (transcripts != null) {
                transcripts.appendAssistant(sid, result.message().content(),
                        result.message().thinking(), result.usage(), result.estimated());
            }
            tracker.recordSessionUsage(result.usage(), result.estimated());
            return new TaskResult("subagent-" + n, result.message().content(), false);
        } catch (RuntimeException e) {
            return new TaskResult("subagent-" + n, safeMessage(e), true);
        } catch (IOException e) {
            return new TaskResult("subagent-" + n, "could not write subagent transcript: " + e.getMessage(), true);
        }
    }

    private AgentRuntime resolveConfig(String agentName) {
        if (agentName == null || agentName.isBlank()) {
            return currentConfig.get();
        }
        try {
            return agents.resolve(agentName);
        } catch (ConfigException e) {
            return null;
        }
    }

    private ToolLoop.Sink sinkFor(ContextBuilder context, TranscriptWriter transcripts) {
        return new ToolLoop.Sink() {
            @Override
            public void assistantWithToolCalls(ChatMessage message, List<ToolCall> calls) {
                context.appendAssistantToolCalls(calls);
                if (transcripts != null) {
                    try {
                        for (ToolCall call : calls) {
                            transcripts.appendToolCall(sidForWrites(), call.id(), call.name(), call.arguments());
                        }
                    } catch (IOException e) {
                        Warn.warn("could not write subagent transcript: " + e.getMessage());
                    }
                }
            }

            @Override
            public void toolResult(String id, String content, boolean error) {
                context.appendToolResult(id, content);
                if (transcripts != null) {
                    try {
                        transcripts.appendToolResult(sidForWrites(), id, content, error);
                    } catch (IOException e) {
                        Warn.warn("could not write subagent transcript: " + e.getMessage());
                    }
                }
            }
        };
    }

    private UUID sidForWrites() {
        return sessionId.get();
    }

    private static String safeMessage(RuntimeException e) {
        String message = e.getMessage();
        return message == null ? e.getClass().getSimpleName() : message;
    }

    private static void replay(ContextBuilder context, ChatMessage message) {
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

    private static int maxToolRounds(AgentRuntime runtime) {
        Integer value = runtime.agent().maxToolRounds();
        return value == null ? ToolLoop.DEFAULT_MAX_TOOL_ROUNDS : value;
    }
}
