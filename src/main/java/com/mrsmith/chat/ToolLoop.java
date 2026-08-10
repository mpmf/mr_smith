package com.mrsmith.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.mrsmith.io.IO;
import com.mrsmith.provider.ChatMessage;
import com.mrsmith.provider.Provider;
import com.mrsmith.provider.ProviderResponse;
import com.mrsmith.provider.ToolCall;
import com.mrsmith.provider.Usage;
import com.mrsmith.tool.Tool;
import com.mrsmith.tool.ToolException;
import com.mrsmith.tool.ToolResult;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

public final class ToolLoop {

    public static final int DEFAULT_MAX_TOOL_ROUNDS = 32;

    public interface Sink {
        void assistantWithToolCalls(ChatMessage message, List<ToolCall> calls);

        void toolResult(String id, String content, boolean error);
    }

    public record LoopResult(ChatMessage message, Usage usage, boolean estimated) {
    }

    private ToolLoop() {
    }

    public static LoopResult run(ContextBuilder context, Provider provider, List<Tool> tools,
                                 IO io, int maxToolRounds, ToolBudget budget, Sink sink,
                                 ToolApproval approval) {
        UsageAccumulator acc = new UsageAccumulator();
        for (int round = 0; ; round++) {
            ProviderResponse response = provider.send(context.messages(), tools, io::write, io::writeReasoning);
            acc.add(response.usage(), response.usageEstimated());
            ChatMessage message = response.message();
            List<ToolCall> calls = message.toolCalls();
            if (calls == null || calls.isEmpty()) {
                return new LoopResult(message, acc.snapshot(), acc.estimated());
            }
            sink.assistantWithToolCalls(message, calls);
            if (round >= maxToolRounds) {
                if (!userWantsToContinue(io, maxToolRounds)) {
                    String limitContent = roundLimitMessage(maxToolRounds);
                    for (ToolCall call : calls) {
                        sink.toolResult(call.id(), limitContent, false);
                    }
                    return finalAnswer(acc, context, provider, tools, io);
                }
                round = -1;
            }
            boolean budgetStopped = false;
            for (int i = 0; i < calls.size(); i++) {
                ToolCall call = calls.get(i);
                if (budget.exhausted()) {
                    String content = budgetLimitMessage(budget);
                    for (int j = i; j < calls.size(); j++) {
                        sink.toolResult(calls.get(j).id(), content, false);
                    }
                    budgetStopped = true;
                    break;
                }
                ToolResult result = executeTool(call, tools, io, approval);
                budget.record();
                io.writeToolExecution("tool: " + call.name() + "(" + describe(call) + ") -> "
                        + (result.error() ? "error" : "ok"));
                sink.toolResult(call.id(), result.content(), result.error());
            }
            if (budgetStopped) {
                return finalAnswer(acc, context, provider, tools, io);
            }
        }
    }

    private static LoopResult finalAnswer(UsageAccumulator acc, ContextBuilder context, Provider provider,
                                          List<Tool> tools, IO io) {
        ProviderResponse finalResponse = provider.send(context.messages(), tools, io::write, io::writeReasoning);
        acc.add(finalResponse.usage(), finalResponse.usageEstimated());
        return new LoopResult(finalResponse.message(), acc.snapshot(), acc.estimated());
    }

    private static String roundLimitMessage(int maxToolRounds) {
        return "Tool round limit (" + maxToolRounds + ") reached; answer without more tool calls.";
    }

    private static boolean userWantsToContinue(IO io, int maxToolRounds) {
        io.writePrompt("Tool round limit (" + maxToolRounds + ") reached. Continue with "
                + maxToolRounds + " more tool rounds? [y/N] ");
        String answer;
        try {
            answer = io.readLine();
        } catch (IOException e) {
            return false;
        }
        return answer != null && (answer.trim().equalsIgnoreCase("y")
                || answer.trim().equalsIgnoreCase("yes"));
    }

    private static String budgetLimitMessage(ToolBudget budget) {
        return "Session tool call budget exhausted (" + budget.used() + "/" + budget.limit() + "). "
                + "Give a brief status update and tell the user to /reset (or send 'continue') if more work is needed.";
    }

    private enum ConfirmDecision { ALLOW, ALWAYS_ALLOW, DECLINE }

    private static ToolResult executeTool(ToolCall call, List<Tool> tools, IO io, ToolApproval approval) {
        Optional<Tool> found = find(tools, call.name());
        if (found.isEmpty()) {
            return new ToolResult("Unknown tool: " + call.name(), true);
        }
        Tool tool = found.get();
        if (!tool.isReadOnly()) {
            if (!approval.isAlwaysAllowed(tool.name())) {
                ConfirmDecision decision = confirm(call, tool, io);
                if (decision == ConfirmDecision.DECLINE) {
                    return new ToolResult("User declined to run " + call.name() + ".", true);
                }
                if (decision == ConfirmDecision.ALWAYS_ALLOW) {
                    approval.allowAlways(tool.name());
                }
            }
        }
        try {
            return tool.execute(call.arguments());
        } catch (ToolException e) {
            return new ToolResult(e.getMessage(), true);
        }
    }

    private static Optional<Tool> find(List<Tool> tools, String name) {
        for (Tool tool : tools) {
            if (tool.name().equals(name)) {
                return Optional.of(tool);
            }
        }
        return Optional.empty();
    }

    private static ConfirmDecision confirm(ToolCall call, Tool tool, IO io) {
        io.writePrompt("Run " + tool.name() + "(" + describe(call) + ") [y/N/a=always]? ");
        String answer;
        try {
            answer = io.readLine();
        } catch (IOException e) {
            return ConfirmDecision.DECLINE;
        }
        if (answer == null) {
            return ConfirmDecision.DECLINE;
        }
        String trimmed = answer.trim();
        if (trimmed.equalsIgnoreCase("a") || trimmed.equalsIgnoreCase("always")) {
            return ConfirmDecision.ALWAYS_ALLOW;
        }
        if (trimmed.equalsIgnoreCase("y") || trimmed.equalsIgnoreCase("yes")) {
            return ConfirmDecision.ALLOW;
        }
        return ConfirmDecision.DECLINE;
    }

    private static String describe(ToolCall call) {
        JsonNode args = call.arguments();
        for (String key : List.of("command", "path", "filePath", "pattern", "url")) {
            JsonNode value = args != null ? args.get(key) : null;
            if (value != null && value.isTextual()) {
                return value.asText();
            }
        }
        return "";
    }
}
