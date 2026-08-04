package com.mrsmith.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ToolCallTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void carriesIdNameAndArguments() throws Exception {
        JsonNode args = JSON.readTree("{\"command\":\"ls\"}");
        ToolCall call = new ToolCall("call_1", "shell", args);
        assertEquals("call_1", call.id());
        assertEquals("shell", call.name());
        assertEquals(args, call.arguments());
    }
}
