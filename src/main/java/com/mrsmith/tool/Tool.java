package com.mrsmith.tool;

import com.fasterxml.jackson.databind.JsonNode;

public interface Tool {

    String name();

    String description();

    JsonNode parametersSchema();

    boolean isReadOnly();

    ToolResult execute(JsonNode args);
}
