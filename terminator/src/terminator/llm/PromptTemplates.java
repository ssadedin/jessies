package terminator.llm;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.*;
import org.jessies.test.*;

/**
 * Prompt templates: Markdown files with optional front matter, {{> partial}} includes and
 * {{variable}} placeholders. See section 6.4 of the plan.
 *
 * A template is rendered into a system message and a user message, split at a line containing
 * only "---8<---". Templates with "scenario" front matter are the scenarios pass 1 chooses between.
 */
public final class PromptTemplates {
    public static final String MESSAGE_SPLIT = "---8<---";
    private static final int MAX_PARTIAL_DEPTH = 8;
    private static final Pattern PARTIAL = Pattern.compile("\\{\\{>\\s*([\\w.-]+)\\s*\\}\\}");
    private static final Pattern VARIABLE = Pattern.compile("\\{\\{\\s*([A-Za-z_]\\w*)\\s*\\}\\}");

    /**
     * A parsed template file.
     *
     * @param name the file name without ".md"
     * @param frontMatter "key: value" pairs from between the leading "---" lines
     * @param body everything after the front matter
     */
    public record Template(String name, Map<String, String> frontMatter, String body) {
        public Optional<String> scenario() {
            return Optional.ofNullable(frontMatter.get("scenario"));
        }
    }

    public record Scenario(String name, String description, String templateName) {
    }

    /**
     * @param unknownVariables placeholders with no value, which are left in the text as they were
     */
    public record RenderedPrompt(String system, String user, Set<String> unknownVariables) {
    }

    private final Map<String, Template> templates;

    private PromptTemplates(Map<String, Template> templates) {
        this.templates = Map.copyOf(templates);
    }

    /**
     * Loads the *.md files from each directory in turn, so a file in a later directory (the user's)
     * replaces a file with the same name in an earlier one (the bundled defaults). Missing directories
     * are fine; unreadable files are skipped and described in the warnings list.
     */
    public static PromptTemplates load(List<Path> directories, List<String> warnings) {
        LinkedHashMap<String, String> sources = new LinkedHashMap<>();
        for (Path directory : directories) {
            if (!Files.isDirectory(directory)) {
                continue;
            }
            try (Stream<Path> files = Files.list(directory)) {
                for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".md")).sorted().toList()) {
                    try {
                        String fileName = file.getFileName().toString();
                        sources.put(fileName.substring(0, fileName.length() - ".md".length()), Files.readString(file, StandardCharsets.UTF_8));
                    } catch (IOException ex) {
                        warnings.add("Couldn't read prompt template " + file + ": " + ex.getMessage());
                    }
                }
            } catch (IOException ex) {
                warnings.add("Couldn't list prompt templates in " + directory + ": " + ex.getMessage());
            }
        }
        return fromSources(sources);
    }

    static PromptTemplates fromSources(Map<String, String> nameToText) {
        HashMap<String, Template> templates = new HashMap<>();
        nameToText.forEach((name, text) -> templates.put(name, parse(name, text)));
        return new PromptTemplates(templates);
    }

    static Template parse(String name, String text) {
        text = text.replace("\r\n", "\n");
        LinkedHashMap<String, String> frontMatter = new LinkedHashMap<>();
        if (text.startsWith("---\n")) {
            int end = text.indexOf("\n---\n", 3);
            if (end != -1) {
                for (String line : text.substring(4, end).split("\n")) {
                    int colon = line.indexOf(':');
                    if (colon > 0) {
                        frontMatter.put(line.substring(0, colon).strip(), line.substring(colon + 1).strip());
                    }
                }
                text = text.substring(end + "\n---\n".length());
            }
        }
        return new Template(name, frontMatter, text);
    }

    public Optional<Template> template(String name) {
        return Optional.ofNullable(templates.get(name));
    }

    /**
     * Returns the scenarios, sorted by name.
     */
    public List<Scenario> scenarios() {
        return templates.values().stream()
                .filter(template -> template.scenario().isPresent())
                .map(template -> new Scenario(template.scenario().get(), template.frontMatter().getOrDefault("description", ""), template.name()))
                .sorted(Comparator.comparing(Scenario::name))
                .toList();
    }

    /**
     * Renders the named template. Partials are expanded first, so they can contain variables;
     * variable values are inserted as-is and never scanned for placeholders, so terminal text
     * containing "{{" is safe.
     *
     * @throws IllegalArgumentException if the template or a partial doesn't exist, or partials nest too deeply
     */
    public RenderedPrompt render(String name, Map<String, String> variables) {
        Template template = template(name).orElseThrow(() -> new IllegalArgumentException("No prompt template named \"" + name + "\""));
        String expanded = expandPartials(template.body(), name, 0);

        TreeSet<String> unknown = new TreeSet<>();
        Matcher matcher = VARIABLE.matcher(expanded);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String value = variables.get(matcher.group(1));
            if (value == null) {
                unknown.add(matcher.group(1));
                value = matcher.group();
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(result);

        String text = result.toString();
        Matcher split = Pattern.compile("^[ \\t]*" + Pattern.quote(MESSAGE_SPLIT) + "[ \\t]*$", Pattern.MULTILINE).matcher(text);
        if (split.find()) {
            return new RenderedPrompt(text.substring(0, split.start()).strip(), text.substring(split.end()).strip(), unknown);
        }
        return new RenderedPrompt("", text.strip(), unknown);
    }

    private String expandPartials(String text, String includedFrom, int depth) {
        Matcher matcher = PARTIAL.matcher(text);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String partialName = matcher.group(1);
            if (depth >= MAX_PARTIAL_DEPTH) {
                throw new IllegalArgumentException("Prompt template partials nest too deeply (a cycle?) at \"" + partialName + "\" in \"" + includedFrom + "\"");
            }
            Template partial = template(partialName).orElseThrow(() -> new IllegalArgumentException("Prompt template \"" + includedFrom + "\" includes missing partial \"" + partialName + "\""));
            // Trailing newlines would otherwise leave blank lines wherever a partial is included.
            String expanded = expandPartials(partial.body().stripTrailing(), partialName, depth + 1);
            matcher.appendReplacement(result, Matcher.quoteReplacement(expanded));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    @Test private static void testParseFrontMatter() {
        Template template = parse("command-error", "---\r\nscenario: command-error\r\ndescription: The last command failed: badly.\r\n---\r\nBody {{screen}}\r\n");
        Assert.equals(template.scenario(), Optional.of("command-error"));
        Assert.equals(template.frontMatter().get("description"), "The last command failed: badly.");
        Assert.equals(template.body(), "Body {{screen}}\n");

        Assert.equals(parse("plain", "No front matter\n---\nhere").frontMatter(), Map.of());
        Assert.equals(parse("unterminated", "---\nscenario: x\nno end").body(), "---\nscenario: x\nno end");
    }

    @Test private static void testRender() {
        PromptTemplates templates = fromSources(Map.of(
                "_system", "You help a user at a terminal.\n",
                "_output-format", "---\nnote: partials can have front matter too\n---\nMarker: {{comment_marker}}\n",
                "explicit-request", "---\nscenario: explicit-request\ndescription: The user typed a request.\n---\n{{> _system}}\n## About the user\n{{user_context}}\n---8<---\n## Terminal\n{{screen}}\n## Request\n{{request}} {{mystery}}\n{{> _output-format}}\n"));
        RenderedPrompt prompt = templates.render("explicit-request", Map.of(
                "user_context", "Bioinformatician.",
                "screen", "$ echo '{{request}}' $1 \\n",
                "request", "list big files",
                "comment_marker", "#"));
        Assert.equals(prompt.system(), "You help a user at a terminal.\n## About the user\nBioinformatician.");
        Assert.equals(prompt.user(), "## Terminal\n$ echo '{{request}}' $1 \\n\n## Request\nlist big files {{mystery}}\nMarker: #");
        Assert.equals(prompt.unknownVariables(), Set.of("mystery"));

        Assert.equals(templates.scenarios(), List.of(new Scenario("explicit-request", "The user typed a request.", "explicit-request")));

        // Without a split, everything is the user message.
        Assert.equals(fromSources(Map.of("t", "Just {{x}}")).render("t", Map.of("x", "this")), new RenderedPrompt("", "Just this", Set.of()));
    }

    @Test private static void testRenderErrors() {
        checkRenderFails(fromSources(Map.of()), "missing", "No prompt template named \"missing\"");
        checkRenderFails(fromSources(Map.of("t", "{{> nope}}")), "t", "includes missing partial \"nope\"");
        checkRenderFails(fromSources(Map.of("a", "{{> b}}", "b", "{{> a}}")), "a", "nest too deeply");
    }

    private static void checkRenderFails(PromptTemplates templates, String name, String expectedMessage) {
        try {
            templates.render(name, Map.of());
            Assert.failure("expected rendering \"" + name + "\" to fail");
        } catch (IllegalArgumentException ex) {
            Assert.contains(ex.getMessage(), expectedMessage);
        }
    }

    @Test private static void testLoadUserOverrides() throws IOException {
        Path root = Files.createTempDirectory("terminator-prompts-test");
        try {
            Path bundled = Files.createDirectories(root.resolve("bundled"));
            Path user = Files.createDirectories(root.resolve("user"));
            Files.writeString(bundled.resolve("general.md"), "bundled general");
            Files.writeString(bundled.resolve("_system.md"), "bundled system");
            Files.writeString(bundled.resolve("notes.txt"), "not a template");
            Files.writeString(user.resolve("general.md"), "user general");
            Files.writeString(user.resolve("my-scenario.md"), "---\nscenario: my-scenario\ndescription: Mine.\n---\n{{> _system}}");

            ArrayList<String> warnings = new ArrayList<>();
            PromptTemplates templates = load(List.of(bundled, user, root.resolve("missing")), warnings);
            Assert.equals(warnings, List.of());
            Assert.equals(templates.render("general", Map.of()).user(), "user general");
            Assert.equals(templates.render("my-scenario", Map.of()).user(), "bundled system");
            Assert.equals(templates.template("notes"), Optional.empty());
        } finally {
            try (Stream<Path> paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
        }
    }
}
