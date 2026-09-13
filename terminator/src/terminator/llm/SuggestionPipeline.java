package terminator.llm;

import java.nio.file.*;
import java.util.*;
import org.jessies.test.*;
import terminator.llm.RequestDetector.DetectedRequest;

/**
 * Turns a terminal snapshot into the chat request to send, without doing any I/O itself, so the same
 * code serves both suggestions and "Preview Request...".
 *
 * Phase 2 is a single pass: a request detected on the cursor line uses the "explicit-request"
 * template, and anything else uses "general". Phase 4 adds a classification pass before this.
 */
public final class SuggestionPipeline {
    public static final String EXPLICIT_REQUEST_TEMPLATE = "explicit-request";
    public static final String GENERAL_TEMPLATE = "general";

    // However long the user's context is, keep at least this much of the terminal.
    private static final int MINIMUM_SCREEN_BUDGET = 1000;

    /**
     * @param templateName the template used
     * @param request the request found on the cursor line, if any (its columns refer to the unredacted line)
     * @param system the rendered system message (after redaction)
     * @param user the rendered user message (after redaction)
     * @param redactionCount how many secrets were redacted
     * @param warnings problems worth showing in a preview or debug log, such as unreadable files
     */
    public record PreparedPrompt(String templateName, Optional<DetectedRequest> request, OpenAiClient.ChatRequest chatRequest, String system, String user, int redactionCount, List<String> warnings) {
    }

    private SuggestionPipeline() {
    }

    /**
     * @throws IllegalArgumentException if the template is missing or broken
     */
    public static PreparedPrompt prepare(TerminalSnapshot snapshot, LlmSettings settings, LlmFiles.Loaded files) {
        ArrayList<String> warnings = new ArrayList<>(files.warnings());

        // In a full-screen application the cursor line is whatever's being edited, such as a line of code, not a prompt.
        Optional<DetectedRequest> request = snapshot.alternateBuffer() ? Optional.empty() : RequestDetector.detect(snapshot.cursorLine(), snapshot.cursorColumn(), settings.commentMarkers());
        String templateName = request.isPresent() ? EXPLICIT_REQUEST_TEMPLATE : GENERAL_TEMPLATE;

        String userContext = files.userContext().isEmpty() ? "(Nothing provided.)" : files.userContext();
        TerminalSnapshot trimmed = snapshot.withBudget(Math.max(settings.contextChars() - files.userContext().length(), Math.min(MINIMUM_SCREEN_BUDGET, settings.contextChars())));

        Redactor redactor = settings.redactSecrets() ? files.redactor() : null;
        java.util.function.UnaryOperator<String> redact = text -> (redactor == null) ? text : redactor.redact(text).text();
        // The cursor line and request are parts of the screen, so only the screen's redactions are counted.
        Redactor.Result screen = (redactor == null) ? new Redactor.Result(trimmed.text(), 0) : redactor.redact(trimmed.text());

        HashMap<String, String> variables = new HashMap<>();
        variables.put("screen", screen.text());
        variables.put("cursor_line", redact.apply(snapshot.cursorLine().stripTrailing()));
        variables.put("screen_kind", snapshot.alternateBuffer() ? "full-screen application (such as vim, less or top)" : "shell session");
        variables.put("title", redact.apply(snapshot.title()));
        variables.put("os", System.getProperty("os.name", "unknown"));
        variables.put("request", request.map(r -> redact.apply(r.text())).orElse(""));
        variables.put("comment_marker", request.map(DetectedRequest::marker).orElse("#"));
        variables.put("user_context", userContext);
        variables.put("scenario", templateName);

        PromptTemplates.RenderedPrompt rendered = files.templates().render(templateName, variables);
        if (!rendered.unknownVariables().isEmpty()) {
            warnings.add("Template \"" + templateName + "\" uses unknown variables: " + String.join(", ", rendered.unknownVariables()));
        }

        ArrayList<OpenAiClient.Message> messages = new ArrayList<>();
        if (!rendered.system().isEmpty()) {
            messages.add(OpenAiClient.Message.system(rendered.system()));
        }
        messages.add(OpenAiClient.Message.user(rendered.user()));
        OpenAiClient.ChatRequest chatRequest = OpenAiClient.ChatRequest.of(settings.model(), messages);
        return new PreparedPrompt(templateName, request, chatRequest, rendered.system(), rendered.user(), screen.redactionCount(), List.copyOf(warnings));
    }

    private static LlmSettings testSettings(boolean redact) {
        return new LlmSettings(true, "http://localhost:11434/v1", "test-model", "test-model", Optional.empty(), 8000, false, false, redact, List.of("#", "--", "//"), java.time.Duration.ofSeconds(30), 85, false);
    }

    private static final PromptTemplates TEST_TEMPLATES = PromptTemplates.fromSources(Map.of(
            "explicit-request", "About: {{user_context}}\n---8<---\n<terminal>\n{{screen}}\n</terminal>\nRequest ({{comment_marker}}): {{request}}",
            "general", "{{os}}\n---8<---\n{{screen_kind}}: {{screen}} {{title}}"));

    @Test private static void testExplicitRequest() {
        TerminalSnapshot snapshot = TerminalSnapshot.fromLines(List.of("$ export API_KEY=abc123", "$ # why does curl fail with token=xyz"), "$ # why does curl fail with token=xyz", 37, false, "me@box: ~", 80, 8000);
        LlmFiles.Loaded files = new LlmFiles.Loaded(TEST_TEMPLATES, Redactor.withBuiltInPatterns(), "I work on genomics pipelines.", List.of());
        PreparedPrompt prompt = prepare(snapshot, testSettings(true), files);
        Assert.equals(prompt.templateName(), EXPLICIT_REQUEST_TEMPLATE);
        Assert.equals(prompt.request().map(DetectedRequest::text), Optional.of("why does curl fail with token=xyz"));
        Assert.equals(prompt.system(), "About: I work on genomics pipelines.");
        Assert.equals(prompt.user(), "<terminal>\n$ export API_KEY=[REDACTED]\n$ # why does curl fail with token=[REDACTED]\n</terminal>\nRequest (#): why does curl fail with token=[REDACTED]");
        Assert.equals(prompt.redactionCount(), 2);
        Assert.equals(prompt.chatRequest().messages(), List.of(OpenAiClient.Message.system(prompt.system()), OpenAiClient.Message.user(prompt.user())));
        Assert.equals(prompt.chatRequest().model(), "test-model");

        // Redaction can be turned off.
        Assert.equals(prepare(snapshot, testSettings(false), files).redactionCount(), 0);
    }

    @Test private static void testGeneralAndFullScreen() {
        LlmFiles.Loaded files = new LlmFiles.Loaded(TEST_TEMPLATES, Redactor.withBuiltInPatterns(), "", List.of("a load warning"));
        // A "#" comment in vim isn't a request.
        TerminalSnapshot vim = TerminalSnapshot.fromLines(List.of("x = 1  # set x"), "x = 1  # set x", 14, true, "vim", 80, 8000);
        PreparedPrompt prompt = prepare(vim, testSettings(true), files);
        Assert.equals(prompt.templateName(), GENERAL_TEMPLATE);
        Assert.equals(prompt.request(), Optional.empty());
        Assert.startsWith(prompt.user(), "full-screen application");
        Assert.equals(prompt.warnings(), List.of("a load warning"));
    }

    @Test private static void testUserContextSharesTheBudget() {
        StringBuilder longContext = new StringBuilder();
        for (int i = 0; i < 100; ++i) {
            longContext.append("context ");
        }
        LlmSettings settings = new LlmSettings(true, "e", "m", "m", Optional.empty(), 1400, false, false, false, List.of("#"), java.time.Duration.ofSeconds(1), 85, false);
        ArrayList<String> lines = new ArrayList<>();
        for (int i = 0; i < 200; ++i) {
            lines.add("line " + i);
        }
        TerminalSnapshot snapshot = TerminalSnapshot.fromLines(lines, "line 199", 8, false, "", 80, settings.contextChars());
        LlmFiles.Loaded files = new LlmFiles.Loaded(TEST_TEMPLATES, Redactor.withBuiltInPatterns(), longContext.toString().strip(), List.of());
        PreparedPrompt prompt = prepare(snapshot, settings, files);
        // 1400 - 799 characters of context leaves 601 for the screen, but at least 1000 is kept.
        String screen = prompt.user().substring(prompt.user().indexOf(": ") + 2);
        Assert.lt(screen.length(), 1000);
        Assert.gt(screen.length(), 900);
    }

    @Test private static void testBundledTemplatesRender() {
        Optional<Path> bundled = LlmFiles.bundledPromptsDirectory().filter(Files::isDirectory);
        if (bundled.isEmpty()) {
            Assert.failure("bundled prompt templates not found; org.jessies.projectRoot=" + System.getProperty("org.jessies.projectRoot"));
        }
        ArrayList<String> warnings = new ArrayList<>();
        PromptTemplates templates = PromptTemplates.load(List.of(bundled.get()), warnings);
        LlmFiles.Loaded files = new LlmFiles.Loaded(templates, Redactor.withBuiltInPatterns(), "", warnings);
        for (String cursorLine : List.of("$ # list big files", "$ ")) {
            TerminalSnapshot snapshot = TerminalSnapshot.fromLines(List.of("$ make", "make: *** No targets.  Stop.", cursorLine), cursorLine, cursorLine.length(), false, "me@box: ~", 80, 8000);
            PreparedPrompt prompt = prepare(snapshot, testSettings(true), files);
            Assert.equals(prompt.warnings(), List.of());
            Assert.contains(prompt.system(), "Terminator");
            Assert.contains(prompt.user(), "make: *** No targets.  Stop.");
            Assert.contains(prompt.user(), "COMMAND: <the command>");
            Assert.equals(prompt.user().contains("{{"), false);
        }
    }
}
