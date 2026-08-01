package com.mrsmith.provider;

public class ProviderException extends RuntimeException {

    private final String partialContent;
    private final String partialThinking;

    public ProviderException(String message) {
        this(message, null, null, null);
    }

    public ProviderException(String message, Throwable cause) {
        this(message, cause, null, null);
    }

    public ProviderException(String message, Throwable cause, String partialContent) {
        this(message, cause, partialContent, null);
    }

    public ProviderException(String message, Throwable cause, String partialContent, String partialThinking) {
        super(message, cause);
        this.partialContent = partialContent;
        this.partialThinking = partialThinking;
    }

    public boolean hasPartialContent() {
        return partialContent != null;
    }

    public String partialContent() {
        return partialContent;
    }

    public String partialThinking() {
        return partialThinking;
    }
}
