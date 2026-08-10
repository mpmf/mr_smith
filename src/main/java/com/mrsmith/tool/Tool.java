package com.mrsmith.tool;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

public interface Tool {

    String name();

    String description();

    JsonNode parametersSchema();

    boolean isReadOnly();

    default ApprovalCheck approvalCheck(JsonNode args) {
        return isReadOnly() ? null : new ApprovalCheck(List.of(name()), null);
    }

    ToolResult execute(JsonNode args);

    record ApprovalCheck(List<String> keys, String reason) {
    }
}
