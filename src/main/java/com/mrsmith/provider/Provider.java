package com.mrsmith.provider;

import com.mrsmith.tool.Tool;

import java.util.List;
import java.util.function.Consumer;

public interface Provider {

    ProviderResponse send(List<ChatMessage> context, List<Tool> tools,
                          Consumer<String> tokenSink, Consumer<String> reasoningSink);
}
