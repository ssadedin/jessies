package terminator.llm;

import com.google.gson.*;
import java.util.*;
import java.util.function.*;
import org.jessies.test.*;

/**
 * Parses the body of an OpenAI-compatible /chat/completions response, one line at a time.
 *
 * Streaming responses are server-sent events whose data is a JSON chunk with the next piece of
 * text in choices[0].delta.content, ending with "data: [DONE]". Servers that ignore "stream": true
 * send a single JSON object instead, which is handled too.
 */
final class ChatStreamParser {
    private final Consumer<String> onContent;
    private final StringBuilder content = new StringBuilder();
    private final StringBuilder eventData = new StringBuilder();
    private final StringBuilder nonEventBody = new StringBuilder();
    private boolean hasEventData = false;
    private boolean sawEvent = false;
    private boolean done = false;
    private String finishReason = null;

    ChatStreamParser(Consumer<String> onContent) {
        this.onContent = onContent;
    }

    /**
     * Handles one line of the response body, without its line terminator.
     *
     * @throws LlmException if the server reports an error or sends something unparseable
     */
    void acceptLine(String line) {
        if (done) {
            return;
        }
        if (line.isEmpty()) {
            dispatchEvent();
        } else if (line.startsWith("data:")) {
            // Per the SSE specification, one optional space follows the colon, and multiple data lines join with newlines.
            String value = line.substring(line.startsWith("data: ") ? 6 : 5);
            if (hasEventData) {
                eventData.append('\n');
            }
            eventData.append(value);
            hasEventData = true;
        } else if (line.startsWith(":") || line.startsWith("event:") || line.startsWith("id:") || line.startsWith("retry:")) {
            // Keep-alive comments and fields we don't need.
        } else {
            nonEventBody.append(line).append('\n');
        }
    }

    /**
     * Handles the end of the body.
     */
    void finish() {
        dispatchEvent();
        if (!sawEvent && !nonEventBody.toString().isBlank()) {
            JsonObject response = parseObject(nonEventBody.toString());
            throwIfError(response);
            firstChoice(response).map(choice -> choice.getAsJsonObject("message")).map(message -> stringOrNull(message, "content")).ifPresent(this::appendContent);
            firstChoice(response).map(choice -> stringOrNull(choice, "finish_reason")).ifPresent(reason -> finishReason = reason);
            done = true;
        }
    }

    boolean isDone() {
        return done;
    }

    String content() {
        return content.toString();
    }

    Optional<String> finishReason() {
        return Optional.ofNullable(finishReason);
    }

    private void dispatchEvent() {
        if (!hasEventData) {
            return;
        }
        String data = eventData.toString();
        eventData.setLength(0);
        hasEventData = false;
        sawEvent = true;
        if (data.strip().equals("[DONE]")) {
            done = true;
            return;
        }
        JsonObject chunk = parseObject(data);
        throwIfError(chunk);
        firstChoice(chunk).ifPresent(choice -> {
            // Reasoning models also send delta.reasoning_content or delta.reasoning; we don't show those.
            JsonObject delta = choice.has("delta") && choice.get("delta").isJsonObject() ? choice.getAsJsonObject("delta") : null;
            if (delta != null) {
                String text = stringOrNull(delta, "content");
                if (text != null) {
                    appendContent(text);
                }
            }
            String reason = stringOrNull(choice, "finish_reason");
            if (reason != null) {
                finishReason = reason;
            }
        });
    }

    private void appendContent(String text) {
        if (!text.isEmpty()) {
            content.append(text);
            onContent.accept(text);
        }
    }

    private static JsonObject parseObject(String json) {
        try {
            JsonElement element = JsonParser.parseString(json);
            if (element.isJsonObject()) {
                return element.getAsJsonObject();
            }
        } catch (JsonParseException ex) {
            // Fall through.
        }
        throw new LlmException("Unexpected response from the LLM endpoint: " + abbreviate(json.strip()));
    }

    /**
     * Returns the error message in an OpenAI-style {"error": {"message": ...}} or Ollama-style {"error": "..."}
     * response body, or the start of the body if it isn't one of those.
     */
    static String describeErrorBody(String body) {
        try {
            JsonElement element = JsonParser.parseString(body);
            if (element.isJsonObject()) {
                Optional<String> message = errorMessage(element.getAsJsonObject());
                if (message.isPresent()) {
                    return message.get();
                }
            }
        } catch (JsonParseException ex) {
            // Not JSON.
        }
        return abbreviate(body.strip());
    }

    private static void throwIfError(JsonObject object) {
        errorMessage(object).ifPresent(message -> {
            throw new LlmException("The LLM endpoint reported an error: " + message);
        });
    }

    private static Optional<String> errorMessage(JsonObject object) {
        JsonElement error = object.get("error");
        if (error == null || error.isJsonNull()) {
            return Optional.empty();
        }
        if (error.isJsonPrimitive()) {
            return Optional.of(error.getAsString());
        }
        if (error.isJsonObject() && error.getAsJsonObject().has("message")) {
            return Optional.of(error.getAsJsonObject().get("message").getAsString());
        }
        return Optional.of(error.toString());
    }

    private static Optional<JsonObject> firstChoice(JsonObject object) {
        JsonElement choices = object.get("choices");
        if (choices == null || !choices.isJsonArray() || choices.getAsJsonArray().isEmpty() || !choices.getAsJsonArray().get(0).isJsonObject()) {
            return Optional.empty();
        }
        return Optional.of(choices.getAsJsonArray().get(0).getAsJsonObject());
    }

    private static String stringOrNull(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return (element != null && element.isJsonPrimitive()) ? element.getAsString() : null;
    }

    private static String abbreviate(String s) {
        return s.length() <= 300 ? s : s.substring(0, 300) + "...";
    }

    private static ChatStreamParser parseAll(String body, List<String> deltas) {
        ChatStreamParser parser = new ChatStreamParser(deltas::add);
        for (String line : body.split("\r?\n", -1)) {
            parser.acceptLine(line);
            if (parser.isDone()) {
                break;
            }
        }
        parser.finish();
        return parser;
    }

    @Test private static void testOllamaStyleStream() {
        String body = """
                data: {"id":"chatcmpl-1","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"role":"assistant","content":""},"finish_reason":null}]}

                : keep-alive

                data: {"id":"chatcmpl-1","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"role":"assistant","reasoning":"thinking..."},"finish_reason":null}]}

                data: {"id":"chatcmpl-1","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"role":"assistant","content":"COMMAND: "},"finish_reason":null}]}

                data: {"id":"chatcmpl-1","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"role":"assistant","content":"du -sh * | sort -h"},"finish_reason":null}]}

                data: {"id":"chatcmpl-1","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"role":"assistant","content":""},"finish_reason":"stop"}]}

                data: [DONE]

                """;
        ArrayList<String> deltas = new ArrayList<>();
        ChatStreamParser parser = parseAll(body, deltas);
        Assert.equals(deltas, List.of("COMMAND: ", "du -sh * | sort -h"));
        Assert.equals(parser.content(), "COMMAND: du -sh * | sort -h");
        Assert.equals(parser.finishReason(), Optional.of("stop"));
        Assert.equals(parser.isDone(), true);
    }

    @Test private static void testLlamaCppStyleStream() {
        // CRLF line endings, no space after "data:", null content and a final chunk with usage and no choices.
        String body = "data:{\"choices\":[{\"delta\":{\"content\":null},\"finish_reason\":null}]}\r\n\r\n"
                + "data:{\"choices\":[{\"delta\":{\"content\":\"line 1\\nline 2\"},\"finish_reason\":null}]}\r\n\r\n"
                + "data:{\"choices\":[{\"delta\":{},\"finish_reason\":\"length\"}]}\r\n\r\n"
                + "data:{\"choices\":[],\"usage\":{\"completion_tokens\":3}}\r\n\r\n"
                + "data: [DONE]\r\n\r\n";
        ArrayList<String> deltas = new ArrayList<>();
        ChatStreamParser parser = parseAll(body, deltas);
        Assert.equals(parser.content(), "line 1\nline 2");
        Assert.equals(parser.finishReason(), Optional.of("length"));
    }

    @Test private static void testStreamWithoutDoneOrTrailingBlankLine() {
        ChatStreamParser parser = parseAll("data: {\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}", new ArrayList<>());
        Assert.equals(parser.content(), "hi");
    }

    @Test private static void testNonStreamingResponse() {
        String body = "{\n  \"choices\": [{\"message\": {\"role\": \"assistant\", \"content\": \"Hello\"}, \"finish_reason\": \"stop\"}]\n}\n";
        ArrayList<String> deltas = new ArrayList<>();
        ChatStreamParser parser = parseAll(body, deltas);
        Assert.equals(parser.content(), "Hello");
        Assert.equals(deltas, List.of("Hello"));
        Assert.equals(parser.finishReason(), Optional.of("stop"));
    }

    @Test private static void testErrors() {
        checkParseFails("data: {\"error\":{\"message\":\"out of memory\",\"type\":\"server_error\"}}\n\n", "The LLM endpoint reported an error: out of memory");
        checkParseFails("{\"error\":\"model not loaded\"}", "The LLM endpoint reported an error: model not loaded");
        checkParseFails("data: {not json\n\n", "Unexpected response from the LLM endpoint: {not json");
        checkParseFails("<html>Bad Gateway</html>", "Unexpected response from the LLM endpoint: <html>Bad Gateway</html>");

        Assert.equals(describeErrorBody("{\"error\":{\"message\":\"model 'x' not found\"}}"), "model 'x' not found");
        Assert.equals(describeErrorBody("{\"error\":\"invalid model\"}"), "invalid model");
        Assert.equals(describeErrorBody("  404 page not found\n"), "404 page not found");
    }

    private static void checkParseFails(String body, String expectedMessage) {
        try {
            parseAll(body, new ArrayList<>());
            Assert.failure("expected parsing to fail: " + body);
        } catch (LlmException ex) {
            Assert.equals(ex.getMessage(), expectedMessage);
        }
    }
}
