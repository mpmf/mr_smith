package com.mrsmith.provider;

import java.util.List;

public record SseResult(String content, String thinking, List<ToolCall> toolCalls, Usage usage) {
}
