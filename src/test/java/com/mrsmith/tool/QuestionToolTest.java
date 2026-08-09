package com.mrsmith.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mrsmith.io.IO;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuestionToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    static class StubIo implements IO {
        final Deque<String> inputs;
        final List<String> lines = new ArrayList<>();

        StubIo(List<String> inputs) {
            this.inputs = new ArrayDeque<>(inputs);
        }

        @Override
        public String readLine() throws IOException {
            return inputs.poll();
        }

        @Override
        public void write(String text) {
            lines.add(text);
        }

        @Override
        public void writeLine(String line) {
            lines.add(line);
        }

        @Override
        public void writeReasoning(String text) {
            lines.add(text);
        }
    }

    private QuestionTool tool(StubIo io) {
        return new QuestionTool(io);
    }

    @Test
    void picksOptionByNumber() throws Exception {
        StubIo io = new StubIo(List.of("2"));
        ToolResult result = tool(io).execute(JSON.readTree(
                "{\"questions\":[{\"question\":\"Pick one\",\"options\":[{\"label\":\"A\"},{\"label\":\"B\"}]}]}"));
        assertFalse(result.error());
        assertEquals("[\"B\"]", result.content());
        assertTrue(io.lines.stream().anyMatch(l -> l.contains("Pick one")));
        assertTrue(io.lines.stream().anyMatch(l -> l.contains("1. A")));
    }

    @Test
    void picksMultipleByCommaList() throws Exception {
        StubIo io = new StubIo(List.of("1,3"));
        ToolResult result = tool(io).execute(JSON.readTree(
                "{\"questions\":[{\"question\":\"Pick\",\"multiple\":true,\"options\":[{\"label\":\"A\"},{\"label\":\"B\"},{\"label\":\"C\"}]}]}"));
        assertFalse(result.error());
        assertEquals("[[\"A\",\"C\"]]", result.content());
    }

    @Test
    void commaListWithoutMultipleIsFreeText() throws Exception {
        StubIo io = new StubIo(List.of("1,3"));
        ToolResult result = tool(io).execute(JSON.readTree(
                "{\"questions\":[{\"question\":\"Pick\",\"options\":[{\"label\":\"A\"},{\"label\":\"B\"},{\"label\":\"C\"}]}]}"));
        assertFalse(result.error());
        assertEquals("[\"1,3\"]", result.content());
    }

    @Test
    void freeTextFallback() throws Exception {
        StubIo io = new StubIo(List.of("custom answer"));
        ToolResult result = tool(io).execute(JSON.readTree(
                "{\"questions\":[{\"question\":\"Pick\",\"options\":[{\"label\":\"A\"}]}]}"));
        assertEquals("[\"custom answer\"]", result.content());
    }

    @Test
    void outOfRangeNumberIsError() throws Exception {
        StubIo io = new StubIo(List.of("5"));
        ToolResult result = tool(io).execute(JSON.readTree(
                "{\"questions\":[{\"question\":\"Pick\",\"options\":[{\"label\":\"A\"}]}]}"));
        assertTrue(result.error());
        assertEquals("5 is not a valid option", result.content());
    }

    @Test
    void answersMultipleQuestionsInOrder() throws Exception {
        StubIo io = new StubIo(List.of("1", "free"));
        ToolResult result = tool(io).execute(JSON.readTree(
                "{\"questions\":["
                + "{\"question\":\"One\",\"options\":[{\"label\":\"A\"}]},"
                + "{\"question\":\"Two\",\"options\":[{\"label\":\"B\"}]}]}"));
        assertFalse(result.error());
        assertEquals("[\"A\",\"free\"]", result.content());
    }

    @Test
    void eofYieldsEmptyAnswer() throws Exception {
        StubIo io = new StubIo(List.of());
        ToolResult result = tool(io).execute(JSON.readTree(
                "{\"questions\":[{\"question\":\"Pick\",\"options\":[{\"label\":\"A\"}]}]}"));
        assertFalse(result.error());
        assertEquals("[\"\"]", result.content());
    }

    @Test
    void missingQuestionsThrows() {
        StubIo io = new StubIo(List.of());
        assertThrows(ToolException.class, () -> tool(io).execute(JSON.readTree("{}")));
    }

    @Test
    void printsHeaderAndDescription() throws Exception {
        StubIo io = new StubIo(List.of("1"));
        tool(io).execute(JSON.readTree(
                "{\"questions\":[{\"header\":\"Pick\",\"question\":\"Choose\",\"options\":[{\"label\":\"A\",\"description\":\"the first\"}]}]}"));
        assertTrue(io.lines.stream().anyMatch(l -> l.contains("[Pick] Choose")));
        assertTrue(io.lines.stream().anyMatch(l -> l.contains("1. A")));
        assertTrue(io.lines.stream().anyMatch(l -> l.contains("the first")));
    }
}
