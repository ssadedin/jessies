package terminator.llm;

import java.nio.file.*;
import java.util.*;
import java.util.function.*;
import org.jessies.test.*;
import terminator.llm.RequestDetector.DetectedRequest;

/**
 * Turns a terminal snapshot into the requests to send, without doing any I/O itself, so the same code
 * serves both suggestions and "Preview Request...". See section 6.1 of the plan.
 *
 * A request typed as a comment on the cursor line goes straight to the "explicit-request" template.
 * Otherwise, pass 1 asks the classifier which scenario fits (see Classifier), and pass 2 renders that
 * scenario's template. With "Skip classification pass" on, the "general" template is used instead.
 */
public final class SuggestionPipeline {
    public static final String EXPLICIT_REQUEST_TEMPLATE = "explicit-request";
    public static final String EXPLAIN_TEMPLATE = "explain";
    public static final String GENERAL_TEMPLATE = "general";
    public static final String CLASSIFY_TEMPLATE = "classify";

    // However long the user's context is, keep at least this much of the terminal.
    private static final int MINIMUM_SCREEN_BUDGET = 1000;

    /**
     * Which template answers the request, and with what request text.
     *
     * @param templateName the template for pass 2
     * @param insertableRequest a request whose position on the cursor line is known for certain, so a suggested command can replace it
     * @param requestText what the user asked for or seems to need ("" if unknown)
     * @param commentMarker the comment marker the user typed, or "#"
     * @param notes things worth showing in a preview or debug log, such as why the classifier's choice was overridden
     */
    public record Choice(String templateName, Optional<DetectedRequest> insertableRequest, String requestText, String commentMarker, List<String> notes) {
        static Choice explainBecause(String note) {
            return new Choice(EXPLAIN_TEMPLATE, Optional.empty(), "", "#", List.of(note));
        }
    }

    /**
     * @param templateName the template used
     * @param choice the choice that led to this prompt (for a classification prompt, a placeholder)
     * @param system the rendered system message (after redaction)
     * @param user the rendered user message (after redaction)
     * @param redactionCount how many secrets were redacted from the terminal text
     * @param warnings problems worth showing in a preview or debug log, such as unreadable files
     */
    public record PreparedPrompt(String templateName, Choice choice, OpenAiClient.ChatRequest chatRequest, String system, String user, int redactionCount, List<String> warnings) {
        /**
         * The request a suggested command can replace, if any.
         */
        public Optional<DetectedRequest> request() {
            return choice.insertableRequest();
        }
    }

    private SuggestionPipeline() {
    }

    /**
     * Returns the choice if it can be made without asking the classifier: a request detected on the cursor
     * line, or the "general" template when classification is turned off. Returns empty if pass 1 is needed.
     */
    public static Optional<Choice> chooseWithoutClassifier(TerminalSnapshot snapshot, LlmSettings settings) {
        // In a full-screen application the cursor line is whatever's being edited, such as a line of code, not a prompt.
        Optional<DetectedRequest> request = snapshot.alternateBuffer() ? Optional.empty() : RequestDetector.detect(snapshot.cursorLine(), snapshot.cursorColumn(), settings.commentMarkers());
        if (request.isPresent()) {
            return Optional.of(new Choice(EXPLICIT_REQUEST_TEMPLATE, request, request.get().text(), request.get().marker(), List.of()));
        }
        if (settings.skipClassification()) {
            return Optional.of(new Choice(GENERAL_TEMPLATE, Optional.empty(), "", "#", List.of()));
        }
        return Optional.empty();
    }

    /**
     * Prepares the pass 1 request, which asks the classifier model to reply with JSON describing the scenario.
     *
     * @throws IllegalArgumentException if the template is missing or broken
     */
    public static PreparedPrompt prepareClassification(TerminalSnapshot snapshot, LlmSettings settings, LlmFiles.Loaded files) {
        StringBuilder scenarios = new StringBuilder();
        for (PromptTemplates.Scenario scenario : files.templates().scenarios()) {
            scenarios.append("- \"").append(scenario.name()).append("\": ").append(scenario.description()).append('\n');
        }
        Choice placeholder = new Choice(CLASSIFY_TEMPLATE, Optional.empty(), "", "#", List.of());
        PreparedPrompt prompt = render(CLASSIFY_TEMPLATE, snapshot, settings, files, placeholder, Map.of("scenarios", scenarios.toString().strip()), settings.classifierModel());
        OpenAiClient.ChatRequest chatRequest = prompt.chatRequest().withTemperature(0).withJsonResponse();
        return new PreparedPrompt(prompt.templateName(), placeholder, chatRequest, prompt.system(), prompt.user(), prompt.redactionCount(), prompt.warnings());
    }

    /**
     * Prepares the pass 2 request that answers the user using the chosen scenario's template.
     *
     * @throws IllegalArgumentException if the template is missing or broken
     */
    public static PreparedPrompt prepare(TerminalSnapshot snapshot, LlmSettings settings, LlmFiles.Loaded files, Choice choice) {
        return render(choice.templateName(), snapshot, settings, files, choice, Map.of(), settings.model());
    }

    /**
     * Returns what the overlay should say while the chosen template's answer is being written.
     */
    public static String statusFor(Choice choice, PromptTemplates templates) {
        return templates.template(choice.templateName()).map(template -> template.frontMatter().getOrDefault("status", "Answering")).orElse("Answering");
    }

    private static PreparedPrompt render(String templateName, TerminalSnapshot snapshot, LlmSettings settings, LlmFiles.Loaded files, Choice choice, Map<String, String> extraVariables, String model) {
        ArrayList<String> warnings = new ArrayList<>(files.warnings());
        warnings.addAll(choice.notes());

        String userContext = files.userContext().isEmpty() ? "(Nothing provided.)" : files.userContext();
        TerminalSnapshot trimmed = snapshot.withBudget(Math.max(settings.contextChars() - files.userContext().length(), Math.min(MINIMUM_SCREEN_BUDGET, settings.contextChars())));

        Redactor redactor = settings.redactSecrets() ? files.redactor() : null;
        UnaryOperator<String> redact = text -> (redactor == null) ? text : redactor.redact(text).text();
        // The cursor line and request are parts of the screen, so only the screen's redactions are counted.
        Redactor.Result screen = (redactor == null) ? new Redactor.Result(trimmed.text(), 0) : redactor.redact(trimmed.text());

        HashMap<String, String> variables = new HashMap<>();
        variables.put("screen", screen.text());
        variables.put("cursor_line", redact.apply(snapshot.cursorLine().stripTrailing()));
        variables.put("screen_kind", snapshot.alternateBuffer() ? "full-screen application (such as vim, less or top)" : "shell session");
        variables.put("title", redact.apply(snapshot.title()));
        variables.put("os", System.getProperty("os.name", "unknown"));
        variables.put("request", choice.requestText().isBlank() ? "(not stated)" : redact.apply(choice.requestText()));
        variables.put("comment_marker", choice.commentMarker());
        variables.put("user_context", userContext);
        variables.put("scenario", choice.templateName());
        variables.putAll(extraVariables);

        PromptTemplates.RenderedPrompt rendered = files.templates().render(templateName, variables);
        if (!rendered.unknownVariables().isEmpty()) {
            warnings.add("Template \"" + templateName + "\" uses unknown variables: " + String.join(", ", rendered.unknownVariables()));
        }

        ArrayList<OpenAiClient.Message> messages = new ArrayList<>();
        if (!rendered.system().isEmpty()) {
            messages.add(OpenAiClient.Message.system(rendered.system()));
        }
        messages.add(OpenAiClient.Message.user(rendered.user()));
        OpenAiClient.ChatRequest chatRequest = OpenAiClient.ChatRequest.of(model, messages);
        return new PreparedPrompt(templateName, choice, chatRequest, rendered.system(), rendered.user(), screen.redactionCount(), List.copyOf(warnings));
    }

    static LlmSettings testSettings(boolean redact, boolean skipClassification) {
        return new LlmSettings(true, "http://localhost:11434/v1", "test-model", "small-model", Optional.empty(), 8000, skipClassification, false, redact, List.of("#", "--", "//"), java.time.Duration.ofSeconds(30), 85, false);
    }

    private static final PromptTemplates TEST_TEMPLATES = PromptTemplates.fromSources(Map.of(
            "explicit-request", "---\nscenario: explicit-request\nstatus: Answering\n---\nAbout: {{user_context}}\n---8<---\n<terminal>\n{{screen}}\n</terminal>\nRequest ({{comment_marker}}): {{request}}",
            "explain", "---\nscenario: explain\ndescription: Explain it.\nstatus: Explaining\n---\n{{screen_kind}}: {{screen}} ({{request}})",
            "general", "{{os}}\n---8<---\n{{screen_kind}}: {{screen}} {{title}}",
            "classify", "Classify.\n---8<---\n{{scenarios}}\n{{cursor_line}}"));

    @Test private static void testExplicitRequest() {
        TerminalSnapshot snapshot = TerminalSnapshot.fromLines(List.of("$ export API_KEY=abc123", "$ # why does curl fail with token=xyz"), "$ # why does curl fail with token=xyz", 37, false, "me@box: ~", 80, 8000);
        LlmFiles.Loaded files = new LlmFiles.Loaded(TEST_TEMPLATES, Redactor.withBuiltInPatterns(), "I work on genomics pipelines.", List.of());
        Choice choice = chooseWithoutClassifier(snapshot, testSettings(true, false)).get();
        PreparedPrompt prompt = prepare(snapshot, testSettings(true, false), files, choice);
        Assert.equals(prompt.templateName(), EXPLICIT_REQUEST_TEMPLATE);
        Assert.equals(prompt.request().map(DetectedRequest::text), Optional.of("why does curl fail with token=xyz"));
        Assert.equals(prompt.system(), "About: I work on genomics pipelines.");
        Assert.equals(prompt.user(), "<terminal>\n$ export API_KEY=[REDACTED]\n$ # why does curl fail with token=[REDACTED]\n</terminal>\nRequest (#): why does curl fail with token=[REDACTED]");
        Assert.equals(prompt.redactionCount(), 2);
        Assert.equals(prompt.chatRequest().messages(), List.of(OpenAiClient.Message.system(prompt.system()), OpenAiClient.Message.user(prompt.user())));
        Assert.equals(prompt.chatRequest().model(), "test-model");
        Assert.equals(statusFor(choice, TEST_TEMPLATES), "Answering");

        // Redaction can be turned off.
        Assert.equals(prepare(snapshot, testSettings(false, false), files, choice).redactionCount(), 0);
    }

    @Test private static void testChoosingWithoutClassifier() {
        TerminalSnapshot shell = TerminalSnapshot.fromLines(List.of("$ make", "make: *** No targets.  Stop.", "$ "), "$ ", 2, false, "", 80, 8000);
        Assert.equals(chooseWithoutClassifier(shell, testSettings(true, false)), Optional.empty());
        Assert.equals(chooseWithoutClassifier(shell, testSettings(true, true)).map(Choice::templateName), Optional.of(GENERAL_TEMPLATE));
        // A "#" comment in vim isn't a request, so the classifier decides.
        TerminalSnapshot vim = TerminalSnapshot.fromLines(List.of("x = 1  # set x"), "x = 1  # set x", 14, true, "vim", 80, 8000);
        Assert.equals(chooseWithoutClassifier(vim, testSettings(true, false)), Optional.empty());
    }

    @Test private static void testClassificationPrompt() {
        TerminalSnapshot snapshot = TerminalSnapshot.fromLines(List.of("PS> ' what is this"), "PS> ' what is this", 18, false, "", 80, 8000);
        LlmFiles.Loaded files = new LlmFiles.Loaded(TEST_TEMPLATES, Redactor.withBuiltInPatterns(), "", List.of("a load warning"));
        PreparedPrompt prompt = prepareClassification(snapshot, testSettings(true, false), files);
        Assert.equals(prompt.user(), "- \"explain\": Explain it.\n- \"explicit-request\":\nPS> ' what is this");
        Assert.equals(prompt.chatRequest().model(), "small-model");
        Assert.equals(prompt.chatRequest().temperature(), 0.0);
        Assert.equals(prompt.chatRequest().responseFormat(), Map.of("type", "json_object"));
        Assert.equals(prompt.warnings(), List.of("a load warning"));
    }

    @Test private static void testExplainFallback() {
        TerminalSnapshot snapshot = TerminalSnapshot.fromLines(List.of("+ int x = 1;"), "+ int x = 1;", 12, true, "", 80, 8000);
        LlmFiles.Loaded files = new LlmFiles.Loaded(TEST_TEMPLATES, Redactor.withBuiltInPatterns(), "", List.of());
        PreparedPrompt prompt = prepare(snapshot, testSettings(true, false), files, Choice.explainBecause("the classifier's reply wasn't JSON"));
        Assert.equals(prompt.user(), "full-screen application (such as vim, less or top): + int x = 1; ((not stated))");
        Assert.equals(prompt.warnings(), List.of("the classifier's reply wasn't JSON"));
        Assert.equals(statusFor(prompt.choice(), TEST_TEMPLATES), "Explaining");
    }

    @Test private static void testUserContextSharesTheBudget() {
        StringBuilder longContext = new StringBuilder();
        for (int i = 0; i < 100; ++i) {
            longContext.append("context ");
        }
        LlmSettings settings = new LlmSettings(true, "e", "m", "m", Optional.empty(), 1400, true, false, false, List.of("#"), java.time.Duration.ofSeconds(1), 85, false);
        ArrayList<String> lines = new ArrayList<>();
        for (int i = 0; i < 200; ++i) {
            lines.add("line " + i);
        }
        TerminalSnapshot snapshot = TerminalSnapshot.fromLines(lines, "line 199", 8, false, "", 80, settings.contextChars());
        LlmFiles.Loaded files = new LlmFiles.Loaded(TEST_TEMPLATES, Redactor.withBuiltInPatterns(), longContext.toString().strip(), List.of());
        PreparedPrompt prompt = prepare(snapshot, settings, files, chooseWithoutClassifier(snapshot, settings).get());
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
        Assert.equals(templates.scenarios().stream().map(PromptTemplates.Scenario::name).toList(), List.of("command-error", "explain", "explicit-request", "log-error"));

        TerminalSnapshot snapshot = TerminalSnapshot.fromLines(List.of("$ make", "make: *** No targets.  Stop.", "$ "), "$ ", 2, false, "me@box: ~", 80, 8000);
        PreparedPrompt classification = prepareClassification(snapshot, testSettings(true, false), files);
        Assert.equals(classification.warnings(), List.of());
        Assert.contains(classification.user(), "- \"log-error\": ");
        Assert.contains(classification.user(), "make: *** No targets.  Stop.");

        for (PromptTemplates.Scenario scenario : templates.scenarios()) {
            Choice choice = new Choice(scenario.templateName(), Optional.empty(), "fix the build", "#", List.of());
            PreparedPrompt prompt = prepare(snapshot, testSettings(true, false), files, choice);
            Assert.equals(scenario.name() + " " + prompt.warnings(), scenario.name() + " []");
            Assert.contains(prompt.system(), "Terminator");
            Assert.contains(prompt.user(), "make: *** No targets.  Stop.");
            Assert.equals(prompt.user().contains("{{"), false);
        }
        Assert.equals(prepare(snapshot, testSettings(true, true), files, chooseWithoutClassifier(snapshot, testSettings(true, true)).get()).warnings(), List.of());
    }
}
