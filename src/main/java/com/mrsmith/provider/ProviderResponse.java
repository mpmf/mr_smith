package com.mrsmith.provider;

public record ProviderResponse(ChatMessage message, Usage usage, boolean usageEstimated) {
}
