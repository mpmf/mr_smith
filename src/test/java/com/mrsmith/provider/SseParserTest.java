package com.mrsmith.provider;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SseParserTest {

    @Test
    void extractsDeltasInOrder() throws Exception {
        List<String> deltas = new ArrayList<>();
        String sse = """
                data: {"choices":[{"delta":{"content":"Hello"}}]}

                data: {"choices":[{"delta":{"content":" world"}}]}

                data: [DONE]

                """;
        SseResult result = SseParser.consume(new BufferedReader(new StringReader(sse)), deltas::add, s -> { });
        assertEquals("Hello world", result.content());
        assertEquals(List.of("Hello", " world"), deltas);
        assertNull(result.usage());
    }

    @Test
    void ignoresChunksWithoutContent() throws Exception {
        List<String> deltas = new ArrayList<>();
        String sse = """
                data: {"choices":[{"delta":{"role":"assistant"}}]}

                data: {"choices":[{"delta":{"content":"hi"}}]}

                data: [DONE]

                """;
        SseResult result = SseParser.consume(new BufferedReader(new StringReader(sse)), deltas::add, s -> { });
        assertEquals("hi", result.content());
        assertEquals(List.of("hi"), deltas);
    }

    @Test
    void usesPartialTextWhenStreamEndsWithoutDone() throws Exception {
        List<String> deltas = new ArrayList<>();
        String sse = """
                data: {"choices":[{"delta":{"content":"partial"}}]}
                """;
        SseResult result = SseParser.consume(new BufferedReader(new StringReader(sse)), deltas::add, s -> { });
        assertEquals("partial", result.content());
    }

    @Test
    void skipsMalformedLinesAndContinues() throws Exception {
        List<String> deltas = new ArrayList<>();
        String sse = """
                data: not-json

                data: {"choices":[{"delta":{"content":"ok"}}]}

                data: [DONE]

                """;
        SseResult result = SseParser.consume(new BufferedReader(new StringReader(sse)), deltas::add, s -> { });
        assertEquals("ok", result.content());
        assertEquals(List.of("ok"), deltas);
    }

    @Test
    void extractsUsageFromChunk() throws Exception {
        List<String> deltas = new ArrayList<>();
        String sse = """
                data: {"choices":[{"delta":{"content":"hi"}}]}

                data: {"usage":{"prompt_tokens":1200,"completion_tokens":300}}

                data: [DONE]

                """;
        SseResult result = SseParser.consume(new BufferedReader(new StringReader(sse)), deltas::add, s -> { });
        assertEquals("hi", result.content());
        assertEquals(new Usage(1200, 300), result.usage());
    }

    @Test
    void malformedUsageDoesNotBreakStream() throws Exception {
        List<String> deltas = new ArrayList<>();
        String sse = """
                data: {"usage":"oops","choices":[{"delta":{"content":"hi"}}]}

                data: [DONE]

                """;
        SseResult result = SseParser.consume(new BufferedReader(new StringReader(sse)), deltas::add, s -> { });
        assertEquals("hi", result.content());
        assertNull(result.usage());
    }

    @Test
    void emptyUsageObjectYieldsNullUsage() throws Exception {
        List<String> deltas = new ArrayList<>();
        String sse = """
                data: {"usage":{}}

                data: [DONE]

                """;
        SseResult result = SseParser.consume(new BufferedReader(new StringReader(sse)), deltas::add, s -> { });
        assertEquals("", result.content());
        assertNull(result.usage());
    }

    @Test
    void extractsReasoningFromReasoningContent() throws Exception {
        List<String> deltas = new ArrayList<>();
        List<String> reasoning = new ArrayList<>();
        String sse = """
                data: {"choices":[{"delta":{"reasoning_content":"think "}}]}

                data: {"choices":[{"delta":{"reasoning_content":"hard"}}]}

                data: {"choices":[{"delta":{"content":"answer"}}]}

                data: [DONE]

                """;
        SseResult result = SseParser.consume(new BufferedReader(new StringReader(sse)), deltas::add, reasoning::add);
        assertEquals("answer", result.content());
        assertEquals("think hard", result.thinking());
        assertEquals(List.of("think ", "hard"), reasoning);
    }

    @Test
    void fallsBackToReasoningField() throws Exception {
        List<String> reasoning = new ArrayList<>();
        String sse = """
                data: {"choices":[{"delta":{"reasoning":"ponder"}}]}

                data: [DONE]

                """;
        SseResult result = SseParser.consume(new BufferedReader(new StringReader(sse)), s -> { }, reasoning::add);
        assertEquals("ponder", result.thinking());
        assertEquals(List.of("ponder"), reasoning);
    }

    @Test
    void thinkingIsNullWhenNoReasoningFields() throws Exception {
        String sse = """
                data: {"choices":[{"delta":{"content":"hi"}}]}

                data: [DONE]

                """;
        SseResult result = SseParser.consume(new BufferedReader(new StringReader(sse)), s -> { }, s -> { });
        assertEquals("hi", result.content());
        assertNull(result.thinking());
    }
}
