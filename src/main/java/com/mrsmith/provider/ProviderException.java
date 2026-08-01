package com.mrsmith.provider;

public class ProviderException extends RuntimeException {

    private final String partialContent;

    public ProviderException(String message) {
        this(message, null, null);
    }

    public ProviderException(String message, Throwable cause) {
        this(message, cause, null);
    }

    public ProviderException(String message, Throwable cause, String partialContent) {
        super(message, cause);
        this.partialContent = partialContent;
    }

    public boolean hasPartialContent() {
        return partialContent != null;
    }

    public String partialContent() {
        return partialContent;
    }
}
