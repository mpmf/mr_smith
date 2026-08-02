package com.mrsmith.provider;

import java.util.List;
import java.util.function.Consumer;

public interface Provider {

    ProviderResponse send(List<ChatMessage> context, Consumer<String> tokenSink,
                          Consumer<String> reasoningSink);
}
