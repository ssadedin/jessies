package terminator.llm;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * The files that configure LLM suggestions: bundled prompt templates in the installation, and the
 * user's own in ~/.terminator/llm/. They're read afresh for each request, so edits take effect
 * immediately. See section 6.4 of the plan.
 */
public final class LlmFiles {
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
        ArrayList<Path> templateDirectories = new ArrayList<>();
        bundledPromptsDirectory().ifPresentOrElse(templateDirectories::add, () -> warnings.add("Couldn't find Terminator's bundled prompt templates (org.jessies.projectRoot isn't set)."));
        templateDirectories.add(userDirectory.resolve("prompts"));
        PromptTemplates templates = PromptTemplates.load(templateDirectories, warnings);
        Redactor redactor = Redactor.withUserPatterns(userDirectory.resolve("redact-patterns.txt"), warnings);
        String userContext = readOptionalFile(userDirectory.resolve("context.md"), warnings).strip();
        return new Loaded(templates, redactor, userContext, List.copyOf(warnings));
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
}
