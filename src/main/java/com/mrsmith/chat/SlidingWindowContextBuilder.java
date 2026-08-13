package com.mrsmith.chat;

import com.mrsmith.provider.ChatMessage;
import com.mrsmith.provider.Role;
import com.mrsmith.provider.TokenEstimator;
import com.mrsmith.provider.ToolCall;

import java.util.ArrayList;
import java.util.List;

public class SlidingWindowContextBuilder implements ContextBuilder {

    public static final int DEFAULT_BUDGET = 100_000;

    private final List<ChatMessage> system = new ArrayList<>();
    private final List<ChatMessage> turns = new ArrayList<>();

    private int systemTokens;
    private int turnTokens;
    private int budget = DEFAULT_BUDGET;

    @Override
    public void start(String systemPrompt, int windowBudgetTokens) {
        system.clear();
        turns.clear();
        systemTokens = 0;
        turnTokens = 0;
        budget = windowBudgetTokens > 0 ? windowBudgetTokens : DEFAULT_BUDGET;
        if (systemPrompt != null) {
            addSystem(new ChatMessage(Role.SYSTEM, systemPrompt));
        }
    }

    @Override
    public void appendSystem(String content) {
        addSystem(new ChatMessage(Role.SYSTEM, content));
    }

    @Override
    public void appendUser(String content) {
        addTurn(new ChatMessage(Role.USER, content));
    }

    @Override
    public void appendAssistant(String content) {
        addTurn(new ChatMessage(Role.ASSISTANT, content));
    }

    @Override
    public void appendAssistantToolCalls(List<ToolCall> toolCalls) {
        addTurn(new ChatMessage(Role.ASSISTANT, null, null, List.copyOf(toolCalls), null));
    }

    @Override
    public void appendToolResult(String toolCallId, String content) {
        addTurn(new ChatMessage(Role.TOOL, content, null, null, toolCallId));
    }

    @Override
    public List<ChatMessage> messages() {
        List<ChatMessage> result = new ArrayList<>(system.size() + turns.size());
        result.addAll(system);
        result.addAll(turns);
        return List.copyOf(result);
    }

    @Override
    public int estimatedTokens() {
        return systemTokens + turnTokens;
    }

    private void addSystem(ChatMessage message) {
        system.add(message);
        systemTokens += TokenEstimator.estimateMessageTokens(message);
    }

    private void addTurn(ChatMessage message) {
        turns.add(message);
        turnTokens += TokenEstimator.estimateMessageTokens(message);
        trim();
    }

    private void trim() {
        while (systemTokens + turnTokens > budget && userMessageCount() > 1) {
            int drop = indexOfSecondUser();
            for (int i = 0; i < drop; i++) {
                turnTokens -= TokenEstimator.estimateMessageTokens(turns.get(i));
            }
            turns.subList(0, drop).clear();
        }
    }

    private int userMessageCount() {
        int count = 0;
        for (ChatMessage message : turns) {
            if (message.role() == Role.USER) {
                count++;
            }
        }
        return count;
    }

    private int indexOfSecondUser() {
        int seen = 0;
        for (int i = 0; i < turns.size(); i++) {
            if (turns.get(i).role() == Role.USER) {
                seen++;
                if (seen == 2) {
                    return i;
                }
            }
        }
        return turns.size();
    }
}
