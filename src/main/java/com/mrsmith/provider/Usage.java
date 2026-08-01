package com.mrsmith.provider;

public record Usage(Integer promptTokens, Integer completionTokens) {

    public int total() {
        int prompt = promptTokens == null ? 0 : promptTokens;
        int completion = completionTokens == null ? 0 : completionTokens;
        return prompt + completion;
    }
}
