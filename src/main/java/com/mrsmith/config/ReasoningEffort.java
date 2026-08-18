package com.mrsmith.config;

public final class ReasoningEffort {

    private String override;

    public void set(String value) {
        this.override = value;
    }

    public void clear() {
        this.override = null;
    }

    public String override() {
        return override;
    }

    public boolean isSet() {
        return override != null && !override.isBlank();
    }

    public String effective(String configured) {
        return isSet() ? override : configured;
    }
}
