package terminator.llm;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.*;
import org.jessies.test.*;

/**
 * The files that configure LLM suggestions: bundled prompt templates in the installation, and the
 * user's own in ~/.terminator/llm/. They're read afresh for each request, so edits take effect
 * immediately. See section 6.4 of the plan and doc/llm-suggestions.md.
 */
public final class LlmFiles {
    private static final Pattern HTML_COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);

    /**
     * Everything read from disk for one request.
     */
    public record Loaded(PromptTemplates templates, Redactor redactor, String userContext, List<String> warnings) {
    }

    private LlmFiles() {
    }

    /**
     * Returns ~/.terminator/llm (or the equivalent in a custom Terminator dot directory).
     */
    public static Path userDirectory() {
        String dotDirectory = System.getProperty("org.jessies.terminator.dotDirectory");
        Path base = (dotDirectory != null) ? Paths.get(dotDirectory) : Paths.get(System.getProperty("user.home"), ".terminator");
        return base.resolve("llm");
    }

    static Optional<Path> bundledPromptsDirectory() {
        String projectRoot = System.getProperty("org.jessies.projectRoot");
        return Optional.ofNullable(projectRoot).map(root -> Paths.get(root, "lib", "llm", "prompts"));
    }

    public static Loaded load() {
        ArrayList<String> warnings = new ArrayList<>();
        Path userDirectory = userDirectory();
        createStarterFilesIfMissing(userDirectory, warnings);
        ArrayList<Path> templateDirectories = new ArrayList<>();
        bundledPromptsDirectory().ifPresentOrElse(templateDirectories::add, () -> warnings.add("Couldn't find Terminator's bundled prompt templates (org.jessies.projectRoot isn't set)."));
        templateDirectories.add(userDirectory.resolve("prompts"));
        PromptTemplates templates = PromptTemplates.load(templateDirectories, warnings);
        Redactor redactor = Redactor.withUserPatterns(userDirectory.resolve("redact-patterns.txt"), warnings);
        String userContext = stripComments(readOptionalFile(userDirectory.resolve("context.md"), warnings));
        return new Loaded(templates, redactor, userContext, List.copyOf(warnings));
    }

    /**
     * Removes <!-- HTML comments --> from the user's context, so the starter file's guidance isn't sent.
     */
    static String stripComments(String text) {
        return HTML_COMMENT.matcher(text).replaceAll("").strip();
    }

    /**
     * On first use, creates the user's directory with commented starter files explaining what goes where.
     * Does nothing if the directory already exists, so files the user deletes stay deleted.
     */
    public static void createStarterFilesIfMissing(Path directory, List<String> warnings) {
        if (Files.exists(directory)) {
            return;
        }
        try {
            Files.createDirectories(directory.resolve("prompts"));
            Files.writeString(directory.resolve("context.md"), CONTEXT_STARTER, StandardCharsets.UTF_8);
            Files.writeString(directory.resolve("redact-patterns.txt"), REDACT_PATTERNS_STARTER, StandardCharsets.UTF_8);
            String bundled = bundledPromptsDirectory().map(Path::toString).orElse("lib/llm/prompts in the Terminator installation");
            Files.writeString(directory.resolve("prompts").resolve("README.txt"), PROMPTS_README_STARTER.replace("BUNDLED_PROMPTS_DIRECTORY", bundled), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            warnings.add("Couldn't create " + directory + ": " + ex.getMessage());
        }
    }

    private static String readOptionalFile(Path file, List<String> warnings) {
        if (!Files.exists(file)) {
            return "";
        }
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            warnings.add("Couldn't read " + file + ": " + ex.getMessage());
            return "";
        }
    }

    private static final String CONTEXT_STARTER = """
            <!--
            This file is sent with every LLM suggestion, so the model knows about you and your environment.
            Text inside comments like this one is NOT sent: write below the comment.

            For example:

            I'm a bioinformatician working on genomics pipelines (Nextflow, Groovy and Python).
            My laptop is a Mac. The servers I ssh to run Ubuntu 22.04 with Slurm, and data is under /data/projects.
            Prefer ripgrep and fd when they'd help. Keep answers short.

            Keep it brief, because it uses part of the context budget set in Preferences.
            This file isn't redacted, so don't put secrets in it.
            -->
            """;

    private static final String REDACT_PATTERNS_STARTER = """
            # Extra patterns for secrets to remove before anything is sent to the LLM, as well as the
            # built-in ones (private keys, API tokens, passwords in KEY=value pairs and URLs, and so on).
            #
            # One Java regular expression per line. Lines starting with # are ignored.
            #
            # If a pattern has a group named "secret", only that group is replaced, so the model still
            # sees what kind of thing was there. For example, to redact customer IDs:
            #   customer_id=(?<secret>\\d+)
            # Otherwise the whole match is replaced. For example, to redact internal ticket numbers:
            #   \\bPROJ-[0-9]{4,}\\b
            """;

    private static final String PROMPTS_README_STARTER = """
            Your own LLM prompt templates go in this directory, as .md files.

            - A file here replaces the bundled template with the same name. To change one, copy it here
              from BUNDLED_PROMPTS_DIRECTORY
              and edit the copy.
            - A new file with "scenario:" and "description:" front matter adds a scenario that the
              classifier can choose. The description is what the classifier sees, so make it specific.

            Changes take effect on the next suggestion. Use Edit > LLM > Preview Request... to see exactly
            what would be sent. See doc/llm-suggestions.md in the Terminator source for the template format.
            """;

    @Test private static void testStripComments() {
        Assert.equals(stripComments(CONTEXT_STARTER), "");
        Assert.equals(stripComments("<!-- guidance -->\nI use zsh.\n<!-- more\nguidance -->\nServers run Rocky Linux."), "I use zsh.\n\nServers run Rocky Linux.");
    }

    @Test private static void testStarterFiles() throws IOException {
        Path root = Files.createTempDirectory("terminator-llm-files-test");
        try {
            Path directory = root.resolve("llm");
            ArrayList<String> warnings = new ArrayList<>();
            createStarterFilesIfMissing(directory, warnings);
            Assert.equals(warnings, List.of());
            Assert.equals(Files.exists(directory.resolve("context.md")), true);
            Assert.equals(Files.exists(directory.resolve("prompts/README.txt")), true);
            // The starter redaction file contains only comments, so it adds no patterns.
            Assert.equals(Redactor.parseUserPatterns(Files.readAllLines(directory.resolve("redact-patterns.txt")), "redact-patterns.txt", warnings), List.of());
            Assert.equals(warnings, List.of());

            // A file the user deleted isn't recreated.
            Files.delete(directory.resolve("context.md"));
            createStarterFilesIfMissing(directory, warnings);
            Assert.equals(Files.exists(directory.resolve("context.md")), false);
        } finally {
            try (Stream<Path> paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
        }
    }
}
