package terminator.llm;

import com.google.gson.*;
import com.google.gson.annotations.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import java.util.stream.*;
import org.jessies.test.*;

/**
 * A minimal streaming client for OpenAI-compatible /chat/completions endpoints
 * (Ollama, llama.cpp, LM Studio, vLLM and others). See section 9.2 of the plan.
 */
public final class OpenAiClient {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final int MAX_ERROR_BODY_CHARS = 4000;

    public record Message(String role, String content) {
        public static Message system(String content) {
            return new Message("system", content);
        }

        public static Message user(String content) {
            return new Message("user", content);
        }
    }

    /**
     * A chat completion request. Null optional fields are left out of the JSON.
     *
     * @param responseFormat for example {"type": "json_object"}
     */
    public record ChatRequest(
            String model,
            List<Message> messages,
            Double temperature,
            @SerializedName("max_tokens") Integer maxTokens,
            @SerializedName("response_format") Map<String, String> responseFormat,
            boolean stream) {
        public static ChatRequest of(String model, List<Message> messages) {
            return new ChatRequest(model, messages, null, null, null, true);
        }

        public ChatRequest withTemperature(double newTemperature) {
            return new ChatRequest(model, messages, newTemperature, maxTokens, responseFormat, stream);
        }

        public ChatRequest withMaxTokens(int newMaxTokens) {
            return new ChatRequest(model, messages, temperature, newMaxTokens, responseFormat, stream);
        }

        public ChatRequest withJsonResponse() {
            return new ChatRequest(model, messages, temperature, maxTokens, Map.of("type", "json_object"), stream);
        }

        public ChatRequest withoutResponseFormat() {
            return new ChatRequest(model, messages, temperature, maxTokens, null, stream);
        }
    }

    /**
     * An in-flight request.
     */
    public static final class ChatCall {
        private final CompletableFuture<String> result = new CompletableFuture<>();
        private final AtomicReference<Stream<String>> body = new AtomicReference<>();
        private volatile CompletableFuture<?> exchange;

        /**
         * Completes with the whole response text, or exceptionally with an LlmException (including
         * when the request takes longer than the timeout), or a CancellationException if cancelled.
         */
        public CompletableFuture<String> result() {
            return result;
        }

        /**
         * Stops the request. Safe to call from any thread, at any time, more than once.
         */
        public void cancel() {
            result.cancel(false);
            release();
        }

        private void release() {
            CompletableFuture<?> currentExchange = exchange;
            if (currentExchange != null) {
                currentExchange.cancel(true);
            }
            Stream<String> currentBody = body.getAndSet(null);
            if (currentBody != null) {
                // Closing the body stream cancels the subscription, which closes the connection and unblocks the reader.
                currentBody.close();
            }
        }
    }

    private final URI chatCompletionsUri;
    private final Optional<String> apiKey;
    private final Duration timeout;
    private final HttpClient httpClient;
    private final Executor executor;

    /**
     * @param endpoint the base URL, such as "http://localhost:11434/v1"
     * @param useSystemProxy whether to use the system proxy settings; false for local endpoints, so a
     *        proxy can't carry "local" requests off the machine
     * @param executor runs the blocking read of each response body
     */
    public OpenAiClient(String endpoint, Optional<String> apiKey, Duration timeout, boolean useSystemProxy, Executor executor) {
        this.chatCompletionsUri = URI.create(endpoint.replaceAll("/+$", "") + "/chat/completions");
        this.apiKey = apiKey;
        this.timeout = timeout;
        this.executor = executor;
        HttpClient.Builder builder = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(timeout);
        if (!useSystemProxy) {
            builder.proxy(HttpClient.Builder.NO_PROXY);
        }
        this.httpClient = builder.build();
    }

    public static OpenAiClient forSettings(LlmSettings settings, Executor executor) {
        return new OpenAiClient(settings.endpoint(), settings.apiKey(), settings.timeout(), settings.allowNonLocalEndpoint(), executor);
    }

    /**
     * True if a request failed only because the server doesn't support its "response_format"
     * (LM Studio answers {"type": "json_object"} with HTTP 400), so it's worth retrying without it.
     */
    public static boolean isResponseFormatRejection(ChatRequest request, Throwable failure) {
        return request.responseFormat() != null && failure instanceof LlmException llmException && llmException.httpStatus().orElse(0) == 400;
    }

    static String toJson(ChatRequest request) {
        return GSON.toJson(request);
    }

    /**
     * Starts a request. onContent is called with each piece of text as it arrives, on the executor's thread.
     * The whole request, including reading the response, must finish within the timeout.
     */
    public ChatCall streamChat(ChatRequest request, Consumer<String> onContent) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(chatCompletionsUri)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(toJson(request), StandardCharsets.UTF_8));
        apiKey.ifPresent(key -> builder.header("Authorization", "Bearer " + key));

        ChatCall call = new ChatCall();
        CompletableFuture<HttpResponse<Stream<String>>> exchange = httpClient.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofLines());
        call.exchange = exchange;
        exchange.whenCompleteAsync((response, failure) -> {
            if (failure != null) {
                call.result.completeExceptionally(translateFailure(failure));
                return;
            }
            call.body.set(response.body());
            if (call.result.isDone()) {
                // Cancelled or timed out while we were waiting for the headers.
                call.release();
                return;
            }
            try (Stream<String> lines = response.body()) {
                call.result.complete(readResponse(response.statusCode(), lines, onContent, call.result));
            } catch (RuntimeException ex) {
                call.result.completeExceptionally(call.result.isDone() ? ex : translateFailure(ex));
            } finally {
                call.body.set(null);
            }
        }, executor);

        CompletableFuture.delayedExecutor(timeout.toMillis(), TimeUnit.MILLISECONDS).execute(() -> {
            if (call.result.completeExceptionally(new LlmException("The LLM endpoint " + chatCompletionsUri + " didn't finish responding within " + timeout.toSeconds() + " seconds."))) {
                call.release();
            }
        });
        return call;
    }

    private static String readResponse(int statusCode, Stream<String> lines, Consumer<String> onContent, CompletableFuture<String> result) {
        Iterator<String> iterator = lines.iterator();
        if (statusCode != 200) {
            StringBuilder errorBody = new StringBuilder();
            while (iterator.hasNext() && errorBody.length() < MAX_ERROR_BODY_CHARS) {
                errorBody.append(iterator.next()).append('\n');
            }
            throw new LlmException("The LLM endpoint returned HTTP " + statusCode + ": " + ChatStreamParser.describeErrorBody(errorBody.toString()), OptionalInt.of(statusCode), null);
        }
        ChatStreamParser parser = new ChatStreamParser(onContent);
        while (!parser.isDone() && !result.isDone() && iterator.hasNext()) {
            parser.acceptLine(iterator.next());
        }
        if (result.isDone()) {
            throw new CancellationException();
        }
        parser.finish();
        return parser.content();
    }

    private LlmException translateFailure(Throwable failure) {
        Throwable cause = (failure instanceof CompletionException && failure.getCause() != null) ? failure.getCause() : failure;
        if (cause instanceof LlmException llmException) {
            return llmException;
        }
        if (cause instanceof HttpTimeoutException || cause instanceof HttpConnectTimeoutException) {
            return new LlmException("The LLM endpoint " + chatCompletionsUri + " didn't respond within " + timeout.toSeconds() + " seconds.", cause);
        }
        if (cause instanceof ConnectException) {
            return new LlmException("Couldn't connect to the LLM endpoint " + chatCompletionsUri + ". Is the server running?", cause);
        }
        return new LlmException("The request to the LLM endpoint " + chatCompletionsUri + " failed: " + cause, cause);
    }

    /**
     * Sends a prompt and prints the streamed reply, for trying out an endpoint by hand:
     * java -cp terminator/.generated/classes:salma-hayek/.generated/classes:terminator/lib/jars/gson-2.14.0.jar terminator.llm.OpenAiClient http://localhost:11434/v1 qwen3:8b "Say hello"
     */
    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            System.err.println("usage: OpenAiClient <endpoint> <model> <prompt>");
            System.exit(2);
        }
        Optional<String> problem = EndpointGuard.check(args[0], false);
        if (problem.isPresent()) {
            System.err.println(problem.get());
            System.exit(1);
        }
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            OpenAiClient client = new OpenAiClient(args[0], Optional.ofNullable(System.getenv("TERMINATOR_LLM_API_KEY")), Duration.ofMinutes(2), false, executor);
            ChatCall call = client.streamChat(ChatRequest.of(args[1], List.of(Message.user(args[2]))), text -> {
                System.out.print(text);
                System.out.flush();
            });
            call.result().get();
            System.out.println();
        } catch (ExecutionException ex) {
            System.err.println();
            System.err.println(ex.getCause().getMessage());
            System.exit(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test private static void testRequestJson() {
        ChatRequest request = ChatRequest.of("qwen3:8b", List.of(Message.system("Be brief."), Message.user("<b>'hi'</b>")));
        Assert.equals(toJson(request), "{\"model\":\"qwen3:8b\",\"messages\":[{\"role\":\"system\",\"content\":\"Be brief.\"},{\"role\":\"user\",\"content\":\"<b>'hi'</b>\"}],\"stream\":true}");
        Assert.equals(toJson(request.withTemperature(0).withMaxTokens(150).withJsonResponse()), "{\"model\":\"qwen3:8b\",\"messages\":[{\"role\":\"system\",\"content\":\"Be brief.\"},{\"role\":\"user\",\"content\":\"<b>'hi'</b>\"}],\"temperature\":0.0,\"max_tokens\":150,\"response_format\":{\"type\":\"json_object\"},\"stream\":true}");
    }

    /**
     * A local server that behaves like an OpenAI-compatible endpoint, for testing the client end to end.
     */
    private static final class FakeServer implements AutoCloseable {
        final com.sun.net.httpserver.HttpServer server;
        final ExecutorService serverExecutor = Executors.newCachedThreadPool();
        final CountDownLatch releaseSlowResponse = new CountDownLatch(1);
        final AtomicReference<String> lastRequestBody = new AtomicReference<>();
        final AtomicReference<String> lastAuthorization = new AtomicReference<>();

        FakeServer() throws IOException {
            server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.setExecutor(serverExecutor);
            server.createContext("/ok/v1/chat/completions", exchange -> {
                recordRequest(exchange);
                streamEvents(exchange, List.of(
                        ": ping",
                        "data: {\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":\"\"}}]}",
                        "data: {\"choices\":[{\"delta\":{\"content\":\"Hello\"}}]}",
                        "data: {\"choices\":[{\"delta\":{\"content\":\", world\"},\"finish_reason\":\"stop\"}]}",
                        "data: [DONE]"), false);
            });
            server.createContext("/missing-model/v1/chat/completions", exchange -> {
                recordRequest(exchange);
                byte[] body = "{\"error\":{\"message\":\"model \\\"nope\\\" not found, try pulling it first\",\"type\":\"api_error\"}}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(404, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.createContext("/slow/v1/chat/completions", exchange -> {
                recordRequest(exchange);
                streamEvents(exchange, List.of("data: {\"choices\":[{\"delta\":{\"content\":\"first\"}}]}"), true);
            });
            server.start();
        }

        String endpoint(String name) {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/" + name + "/v1/";
        }

        private void recordRequest(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
            lastRequestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            lastAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
        }

        private void streamEvents(com.sun.net.httpserver.HttpExchange exchange, List<String> events, boolean thenStall) throws IOException {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                for (String event : events) {
                    out.write((event + "\n\n").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
                if (thenStall) {
                    releaseSlowResponse.await(10, TimeUnit.SECONDS);
                }
            } catch (InterruptedException | IOException ex) {
                // The client went away, which is what the cancellation tests want.
            }
        }

        @Override public void close() {
            releaseSlowResponse.countDown();
            server.stop(0);
            serverExecutor.shutdownNow();
        }
    }

    private static <T> T await(CompletableFuture<T> future, long seconds) throws Exception {
        return future.get(seconds, TimeUnit.SECONDS);
    }

    @Test private static void testStreamingAgainstLocalServer() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (FakeServer server = new FakeServer()) {
            OpenAiClient client = new OpenAiClient(server.endpoint("ok"), Optional.of("sekrit"), Duration.ofSeconds(10), false, executor);
            List<String> deltas = new CopyOnWriteArrayList<>();
            ChatCall call = client.streamChat(ChatRequest.of("test-model", List.of(Message.user("hi"))).withMaxTokens(5), deltas::add);
            Assert.equals(await(call.result(), 10), "Hello, world");
            Assert.equals(deltas, List.of("Hello", ", world"));
            Assert.equals(server.lastAuthorization.get(), "Bearer sekrit");
            Assert.contains(server.lastRequestBody.get(), "\"max_tokens\":5");

            // No API key, no Authorization header.
            OpenAiClient anonymous = new OpenAiClient(server.endpoint("ok"), Optional.empty(), Duration.ofSeconds(10), false, executor);
            await(anonymous.streamChat(ChatRequest.of("test-model", List.of(Message.user("hi"))), text -> {}).result(), 10);
            Assert.equals(server.lastAuthorization.get(), null);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test private static void testHttpErrorAgainstLocalServer() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (FakeServer server = new FakeServer()) {
            OpenAiClient client = new OpenAiClient(server.endpoint("missing-model"), Optional.empty(), Duration.ofSeconds(10), false, executor);
            LlmException failure = expectFailure(client.streamChat(ChatRequest.of("nope", List.of(Message.user("hi"))), text -> {}).result(), LlmException.class);
            Assert.equals(failure.getMessage(), "The LLM endpoint returned HTTP 404: model \"nope\" not found, try pulling it first");
            Assert.equals(failure.httpStatus(), OptionalInt.of(404));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test private static void testConnectionRefused() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            int unusedPort;
            try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
                unusedPort = socket.getLocalPort();
            }
            OpenAiClient client = new OpenAiClient("http://127.0.0.1:" + unusedPort + "/v1", Optional.empty(), Duration.ofSeconds(10), false, executor);
            LlmException failure = expectFailure(client.streamChat(ChatRequest.of("m", List.of(Message.user("hi"))), text -> {}).result(), LlmException.class);
            Assert.contains(failure.getMessage(), "Is the server running?");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test private static void testCancelMidStream() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (FakeServer server = new FakeServer()) {
            OpenAiClient client = new OpenAiClient(server.endpoint("slow"), Optional.empty(), Duration.ofSeconds(30), false, executor);
            CountDownLatch firstDelta = new CountDownLatch(1);
            ChatCall call = client.streamChat(ChatRequest.of("m", List.of(Message.user("hi"))), text -> firstDelta.countDown());
            Assert.equals(firstDelta.await(10, TimeUnit.SECONDS), true);
            long start = System.nanoTime();
            call.cancel();
            Assert.equals(call.result().isCancelled(), true);

            // The single reader thread must be free again, long before the server's 10s stall ends.
            OpenAiClient okClient = new OpenAiClient(server.endpoint("ok"), Optional.empty(), Duration.ofSeconds(30), false, executor);
            Assert.equals(await(okClient.streamChat(ChatRequest.of("m", List.of(Message.user("hi"))), text -> {}).result(), 5), "Hello, world");
            Assert.lt((int) TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start), 5000);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test private static void testTimeoutMidStream() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (FakeServer server = new FakeServer()) {
            OpenAiClient client = new OpenAiClient(server.endpoint("slow"), Optional.empty(), Duration.ofMillis(500), false, executor);
            long start = System.nanoTime();
            LlmException failure = expectFailure(client.streamChat(ChatRequest.of("m", List.of(Message.user("hi"))), text -> {}).result(), LlmException.class);
            Assert.contains(failure.getMessage(), "didn't finish responding within");
            Assert.lt((int) TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start), 5000);

            OpenAiClient okClient = new OpenAiClient(server.endpoint("ok"), Optional.empty(), Duration.ofSeconds(30), false, executor);
            Assert.equals(await(okClient.streamChat(ChatRequest.of("m", List.of(Message.user("hi"))), text -> {}).result(), 5), "Hello, world");
        } finally {
            executor.shutdownNow();
        }
    }

    private static <T extends Throwable> T expectFailure(CompletableFuture<?> future, Class<T> expected) throws Exception {
        try {
            future.get(10, TimeUnit.SECONDS);
        } catch (ExecutionException ex) {
            if (expected.isInstance(ex.getCause())) {
                return expected.cast(ex.getCause());
            }
            throw new AssertionError("expected " + expected.getSimpleName() + " but got " + ex.getCause(), ex.getCause());
        }
        throw new AssertionError("expected " + expected.getSimpleName() + " but the request succeeded");
    }
}
