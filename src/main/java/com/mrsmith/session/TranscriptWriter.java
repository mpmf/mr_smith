package com.mrsmith.session;

import com.mrsmith.provider.Usage;

import java.io.IOException;
import java.util.UUID;

public interface TranscriptWriter {

    void start(UUID sessionId) throws IOException;

    void appendUser(UUID sessionId, String content) throws IOException;

    void appendAssistant(UUID sessionId, String content, String thinking,
                         Usage usage, boolean estimated) throws IOException;
}
