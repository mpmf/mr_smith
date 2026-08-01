package com.mrsmith.provider;

import java.util.List;
import java.util.function.Consumer;

public interface Provider {

    ChatMessage send(List<ChatMessage> history, Consumer<String> tokenSink);
}
