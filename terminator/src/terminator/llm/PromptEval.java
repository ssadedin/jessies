package terminator.llm;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.jessies.test.*;

/**
 * Runs saved terminal screens through the suggestion pipeline against a real model, and reports whether
 * each got the expected scenario and kind of answer. For tuning the prompt templates against local models.
 * See "Tuning the prompts" in doc/llm-suggestions.md.
 *
 * Each fixture is a text file: "key: value" header lines, a line containing only "---", then the terminal
 * text. The last line is the cursor line, with the cursor at its end. Header keys (all optional):
 * expect-scenario, expect-command (yes, no or any), expect-insertable (yes or no), expect-mentions
 * (comma-separated alternatives, any of which counts, case-insensitive), alternate-buffer (true or false), title.
 */
public final class PromptEval {
    record Fixture(String name, Map<String, String> header, List<String> lines) {
        TerminalSnapshot snapshot(int characterBudget) {
            String cursorLine = lines.isEmpty() ? "" : lines.get(lines.size() - 1);
            int width = lines.stream().mapToInt(String::length).max().orElse(80);
            return TerminalSnapshot.fromLines(lines, cursorLine, cursorLine.length(), Boolean.parseBoolean(header.getOrDefault("alternate-buffer", "false")), header.getOrDefault("title", ""), Math.max(80, width), characterBudget);
        }
    }

    record Outcome(String scenario, boolean insertable, SuggestionReply reply, long classifyMillis, long answerMillis, List<String> notes) {
    }

    private PromptEval() {
    }

    static Fixture parseFixture(String name, String text) {
        List<String> all = Arrays.asList(text.replace("\r\n", "\n").split("\n", -1));
        int separator = all.indexOf("---");
        if (separator == -1) {
            throw new IllegalArgumentException(name + ": no \"---\" line between the header and the terminal text");
        }
        LinkedHashMap<String, String> header = new LinkedHashMap<>();
        for (String line : all.subList(0, separator)) {
            int colon = line.indexOf(':');
            if (colon > 0 && !line.startsWith("#")) {
                header.put(line.substring(0, colon).strip().toLowerCase(Locale.ROOT), line.substring(colon + 1).strip());
            }
        }
        ArrayList<String> lines = new ArrayList<>(all.subList(separator + 1, all.size()));
        // A final newline in the file isn't an extra empty cursor line, but trailing spaces on the cursor line matter.
        if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        return new Fixture(name, header, lines);
    }

    /**
     * Returns a description of each failed expectation (empty if they were all met).
     */
    static List<String> check(Fixture fixture, Outcome outcome) {
        ArrayList<String> failures = new ArrayList<>();
        String expectedScenario = fixture.header().get("expect-scenario");
        if (expectedScenario != null && !expectedScenario.equals(outcome.scenario())) {
            failures.add("scenario was " + outcome.scenario() + ", expected " + expectedScenario);
        }
        String expectCommand = fixture.header().getOrDefault("expect-command", "any");
        if (expectCommand.equals("yes") && outcome.reply().command().isEmpty()) {
            failures.add("expected a COMMAND reply");
        } else if (expectCommand.equals("no") && outcome.reply().command().isPresent()) {
            failures.add("expected an explanation, not a COMMAND reply");
        }
        String expectInsertable = fixture.header().get("expect-insertable");
        if (expectInsertable != null && expectInsertable.equals("yes") != outcome.insertable()) {
            failures.add("expected the request " + (expectInsertable.equals("yes") ? "to be" : "not to be") + " insertable");
        }
        String mentions = fixture.header().get("expect-mentions");
        if (mentions != null) {
            String replyText = outcome.reply().displayText().toLowerCase(Locale.ROOT);
            List<String> alternatives = Arrays.stream(mentions.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
            if (alternatives.stream().noneMatch(alternative -> replyText.contains(alternative.toLowerCase(Locale.ROOT)))) {
                failures.add("reply mentions none of: " + String.join(", ", alternatives));
            }
        }
        return failures;
    }

    static Outcome run(Fixture fixture, LlmSettings settings, LlmFiles.Loaded files, OpenAiClient client) throws Exception {
        TerminalSnapshot snapshot = fixture.snapshot(settings.contextChars());
        long classifyMillis = 0;
        SuggestionPipeline.Choice choice = SuggestionPipeline.chooseWithoutClassifier(snapshot, settings).orElse(null);
        if (choice == null) {
            long start = System.nanoTime();
            String classification = complete(client, SuggestionPipeline.prepareClassification(snapshot, settings, files).chatRequest());
            classifyMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            choice = Classifier.interpret(classification, snapshot, files.templates());
        }
        long start = System.nanoTime();
        String answer = complete(client, SuggestionPipeline.prepare(snapshot, settings, files, choice).chatRequest());
        long answerMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        String scenario = files.templates().template(choice.templateName()).flatMap(PromptTemplates.Template::scenario).orElse(choice.templateName());
        return new Outcome(scenario, choice.insertableRequest().isPresent(), SuggestionReply.parse(answer), classifyMillis, answerMillis, choice.notes());
    }

    private static String complete(OpenAiClient client, OpenAiClient.ChatRequest request) throws Exception {
        try {
            return client.streamChat(request, text -> {}).result().get();
        } catch (ExecutionException ex) {
            if (OpenAiClient.isResponseFormatRejection(request, ex.getCause())) {
                return client.streamChat(request.withoutResponseFormat(), text -> {}).result().get();
            }
            throw ex;
        }
    }

    private static void usage() {
        System.err.println("usage: PromptEval --endpoint URL --model MODEL [--classifier-model MODEL] [--context-chars N] [--fixtures DIR] [FIXTURE-NAME...]");
        System.exit(2);
    }

    public static void main(String[] args) throws Exception {
        String endpoint = null;
        String model = null;
        String classifierModel = null;
        int contextChars = 8000;
        Path fixturesDirectory = Paths.get(System.getProperty("org.jessies.projectRoot", "terminator"), "tests", "llm-eval");
        ArrayList<String> only = new ArrayList<>();
        for (int i = 0; i < args.length; ++i) {
            switch (args[i]) {
                case "--endpoint" -> endpoint = args[++i];
                case "--model" -> model = args[++i];
                case "--classifier-model" -> classifierModel = args[++i];
                case "--context-chars" -> contextChars = Integer.parseInt(args[++i]);
                case "--fixtures" -> fixturesDirectory = Paths.get(args[++i]);
                default -> {
                    if (args[i].startsWith("--")) {
                        usage();
                    }
                    only.add(args[i].replaceAll("\\.txt$", ""));
                }
            }
        }
        if (endpoint == null || model == null) {
            usage();
        }
        Optional<String> problem = EndpointGuard.check(endpoint, false);
        if (problem.isPresent()) {
            System.err.println(problem.get());
            System.exit(1);
        }

        LlmSettings settings = new LlmSettings(true, endpoint, model, classifierModel != null ? classifierModel : model, Optional.ofNullable(System.getenv("TERMINATOR_LLM_API_KEY")), contextChars, false, false, true, List.of("#", "--", "//"), Duration.ofMinutes(5), 85, false);
        // The user's own templates, context and redaction patterns are used, so they can be tuned too.
        LlmFiles.Loaded files = LlmFiles.load();
        files.warnings().forEach(warning -> System.out.println("Warning: " + warning));

        List<Path> fixtureFiles;
        try (var paths = Files.list(fixturesDirectory)) {
            fixtureFiles = paths.filter(path -> path.toString().endsWith(".txt")).sorted().toList();
        }
        ExecutorService executor = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "PromptEval");
            thread.setDaemon(true);
            return thread;
        });
        OpenAiClient client = OpenAiClient.forSettings(settings, executor);

        System.out.println("# Prompt evaluation: " + model + (classifierModel != null ? " (classifier " + classifierModel + ")" : "") + " at " + endpoint + "\n");
        int passed = 0;
        int total = 0;
        StringBuilder summary = new StringBuilder("| Fixture | Result | Scenario | Classify | Answer |\n|---|---|---|---|---|\n");
        for (Path file : fixtureFiles) {
            String name = file.getFileName().toString().replaceAll("\\.txt$", "");
            if (!only.isEmpty() && !only.contains(name)) {
                continue;
            }
            ++total;
            Fixture fixture = parseFixture(name, Files.readString(file, StandardCharsets.UTF_8));
            System.out.println("## " + name + "\n");
            try {
                Outcome outcome = run(fixture, settings, files, client);
                List<String> failures = check(fixture, outcome);
                if (failures.isEmpty()) {
                    ++passed;
                }
                String result = failures.isEmpty() ? "PASS" : "FAIL: " + String.join("; ", failures);
                System.out.println("- Result: " + result);
                System.out.println("- Scenario: " + outcome.scenario() + (outcome.insertable() ? " (insertable request)" : "") + ", classify " + outcome.classifyMillis() + "ms, answer " + outcome.answerMillis() + "ms");
                outcome.notes().forEach(note -> System.out.println("- Note: " + note));
                System.out.println("\n```\n" + outcome.reply().displayText() + (outcome.reply().command().isPresent() ? "\n(command: " + outcome.reply().command().get() + ")" : "") + "\n```\n");
                summary.append("| ").append(name).append(" | ").append(failures.isEmpty() ? "PASS" : "FAIL").append(" | ").append(outcome.scenario()).append(" | ").append(outcome.classifyMillis()).append("ms | ").append(outcome.answerMillis()).append("ms |\n");
            } catch (Exception ex) {
                Throwable cause = (ex instanceof ExecutionException && ex.getCause() != null) ? ex.getCause() : ex;
                System.out.println("- Result: ERROR: " + cause.getMessage() + "\n");
                summary.append("| ").append(name).append(" | ERROR | | | |\n");
            }
        }
        System.out.println("## Summary: " + passed + " of " + total + " passed\n\n" + summary);
        System.exit(passed == total ? 0 : 1);
    }

    @Test private static void testParseFixture() {
        Fixture fixture = parseFixture("example", "expect-scenario: explicit-request\nexpect-command: yes\ntitle: me@box: ~\n---\n$ ls\na  b\n$ # count files \n");
        Assert.equals(fixture.header().get("expect-scenario"), "explicit-request");
        Assert.equals(fixture.lines(), List.of("$ ls", "a  b", "$ # count files "));
        TerminalSnapshot snapshot = fixture.snapshot(8000);
        Assert.equals(snapshot.cursorLine(), "$ # count files ");
        Assert.equals(snapshot.cursorColumn(), 16);
        Assert.equals(snapshot.title(), "me@box: ~");
        Assert.equals(RequestDetector.detect(snapshot.cursorLine(), snapshot.cursorColumn(), List.of("#")).map(RequestDetector.DetectedRequest::text), Optional.of("count files"));
    }

    @Test private static void testCheck() {
        Fixture fixture = parseFixture("f", "expect-scenario: command-error\nexpect-command: yes\nexpect-insertable: no\nexpect-mentions: chmod, permission\n---\n$ ./deploy.sh\n");
        Assert.equals(check(fixture, new Outcome("command-error", false, SuggestionReply.parse("COMMAND: chmod +x deploy.sh"), 0, 0, List.of())), List.of());
        Assert.equals(check(fixture, new Outcome("explain", true, SuggestionReply.parse("It's a script."), 0, 0, List.of())), List.of(
                "scenario was explain, expected command-error",
                "expected a COMMAND reply",
                "expected the request not to be insertable",
                "reply mentions none of: chmod, permission"));
    }

    @Test private static void testBundledFixturesParse() throws IOException {
        Path directory = Paths.get(System.getProperty("org.jessies.projectRoot", "terminator"), "tests", "llm-eval");
        if (!Files.isDirectory(directory)) {
            Assert.failure("fixtures not found in " + directory);
        }
        List<String> scenarios = List.of("explicit-request", "command-error", "log-error", "explain");
        try (var paths = Files.list(directory)) {
            for (Path file : paths.filter(path -> path.toString().endsWith(".txt")).toList()) {
                Fixture fixture = parseFixture(file.getFileName().toString(), Files.readString(file));
                Assert.equals(file.getFileName() + " " + scenarios.contains(fixture.header().get("expect-scenario")), file.getFileName() + " true");
                Assert.equals(file.getFileName() + " has lines " + !fixture.lines().isEmpty(), file.getFileName() + " has lines true");
            }
        }
    }
}
