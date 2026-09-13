package terminator.llm;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import org.jessies.test.*;

/**
 * Replaces likely secrets in terminal text before it's sent anywhere. See section 7 of the plan.
 *
 * A pattern with a group named "secret" has just that group replaced, so "password=hunter2"
 * becomes "password=[REDACTED]" and the model still knows what was there. Otherwise the whole
 * match is replaced.
 */
public final class Redactor {
    public static final String REPLACEMENT = "[REDACTED]";

    /**
     * The result of redaction.
     *
     * @param text the redacted text
     * @param redactionCount how many secrets were replaced
     */
    public record Result(String text, int redactionCount) {
    }

    // Keys and values on a line use [ \t] rather than \s so that a "Password:" prompt doesn't
    // swallow the first word of the next line.
    private static final List<Pattern> BUILT_IN_PATTERNS = List.of(
            Pattern.compile("-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----.*?(?:-----END [A-Z0-9 ]*PRIVATE KEY-----|\\z)", Pattern.DOTALL),
            Pattern.compile("\\b(?:AKIA|ASIA)[0-9A-Z]{16}\\b"),
            // Any key containing one of these words (DB_PASSWORD, GITHUB_TOKEN, aws_secret_access_key, "apiKey"),
            // with an optionally quoted value. Deliberately broad: "max_tokens=100" gets redacted too.
            Pattern.compile("(?i)(?<![A-Za-z0-9])[A-Za-z0-9_.-]*(?:password|passwd|secret|token|api[_-]?key)[A-Za-z0-9_.-]*[\"']?[ \\t]*[=:][ \\t]*(?<secret>\"[^\"\\n]*\"|'[^'\\n]*'|[^\\s\"',;]+)"),
            Pattern.compile("(?i)(?<![A-Za-z0-9])pwd[ \\t]*[=:][ \\t]*(?<secret>\\S+)"),
            Pattern.compile("(?i)\\bauthorization:[ \\t]*(?<secret>[^\\s\"']+(?:[ \\t]+[^\\s\"']+)?)"),
            Pattern.compile("(?i)\\bbearer[ \\t]+(?<secret>[A-Za-z0-9._~+/-]+=*)"),
            Pattern.compile("\\bgh[pousr]_[A-Za-z0-9]{36,}\\b"),
            Pattern.compile("\\bgithub_pat_[A-Za-z0-9_]{22,}\\b"),
            Pattern.compile("\\bsk-[A-Za-z0-9_-]{20,}"),
            Pattern.compile("\\bxox[abprs]-[A-Za-z0-9-]{10,}"),
            Pattern.compile("\\beyJ[\\w-]+\\.eyJ[\\w-]+\\.[\\w-]+"),
            Pattern.compile("://[^/\\s:@]+:(?<secret>[^/\\s@]+)@"));

    private final List<Pattern> patterns;

    private Redactor(List<Pattern> patterns) {
        this.patterns = List.copyOf(patterns);
    }

    /**
     * Returns a redactor using only the built-in patterns.
     */
    public static Redactor withBuiltInPatterns() {
        return new Redactor(BUILT_IN_PATTERNS);
    }

    /**
     * Returns a redactor using the built-in patterns plus the user's patterns, one regular expression
     * per line in userPatternsFile (blank lines and lines starting with "#" are ignored). A missing file
     * is fine. Invalid patterns are skipped and described in the warnings list.
     */
    public static Redactor withUserPatterns(Path userPatternsFile, List<String> warnings) {
        ArrayList<Pattern> patterns = new ArrayList<>(BUILT_IN_PATTERNS);
        if (Files.isReadable(userPatternsFile)) {
            try {
                patterns.addAll(parseUserPatterns(Files.readAllLines(userPatternsFile, StandardCharsets.UTF_8), userPatternsFile.toString(), warnings));
            } catch (IOException ex) {
                warnings.add("Couldn't read " + userPatternsFile + ": " + ex.getMessage());
            }
        }
        return new Redactor(patterns);
    }

    static List<Pattern> parseUserPatterns(List<String> lines, String sourceName, List<String> warnings) {
        ArrayList<Pattern> patterns = new ArrayList<>();
        for (int i = 0; i < lines.size(); ++i) {
            String line = lines.get(i).strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            try {
                patterns.add(Pattern.compile(line));
            } catch (PatternSyntaxException ex) {
                warnings.add(sourceName + ":" + (i + 1) + ": invalid pattern: " + ex.getDescription());
            }
        }
        return patterns;
    }

    public Result redact(String text) {
        int count = 0;
        for (Pattern pattern : patterns) {
            boolean hasSecretGroup = pattern.pattern().contains("(?<secret>");
            Matcher matcher = pattern.matcher(text);
            StringBuilder result = new StringBuilder();
            int copiedUpTo = 0;
            while (matcher.find()) {
                int start = hasSecretGroup ? matcher.start("secret") : matcher.start();
                int end = hasSecretGroup ? matcher.end("secret") : matcher.end();
                if (start == end || text.substring(start, end).equals(REPLACEMENT)) {
                    continue;
                }
                result.append(text, copiedUpTo, start).append(REPLACEMENT);
                copiedUpTo = end;
                ++count;
            }
            result.append(text, copiedUpTo, text.length());
            text = result.toString();
        }
        return new Result(text, count);
    }

    private static void checkRedacts(String input, String expected) {
        Assert.equals(withBuiltInPatterns().redact(input).text(), expected);
    }

    @Test private static void testBuiltInPatterns() {
        checkRedacts("export AWS_ACCESS_KEY_ID=AKIAIOSFODNN7EXAMPLE", "export AWS_ACCESS_KEY_ID=[REDACTED]");
        checkRedacts("aws_secret_access_key = wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY", "aws_secret_access_key = [REDACTED]");
        checkRedacts("export GITHUB_TOKEN=abc123", "export GITHUB_TOKEN=[REDACTED]");
        checkRedacts("PASSWORD=\"hunter 2\" make", "PASSWORD=[REDACTED] make");
        checkRedacts("{\"password\": \"hunter2\", \"user\": \"me\"}", "{\"password\": [REDACTED], \"user\": \"me\"}");
        checkRedacts("apiKey: 'abc'", "apiKey: [REDACTED]");
        checkRedacts("mysql -u root pwd=hunter2", "mysql -u root pwd=[REDACTED]");
        checkRedacts("DB_PASSWORD=hunter2 ./run", "DB_PASSWORD=[REDACTED] ./run");
        checkRedacts("mysql --password=hunter2", "mysql --password=[REDACTED]");
        checkRedacts("api_key: abc123", "api_key: [REDACTED]");
        checkRedacts("curl -H 'Authorization: Bearer abc.def' x", "curl -H 'Authorization: [REDACTED]' x");
        checkRedacts("token is Bearer abc.def-ghi", "token is Bearer [REDACTED]");
        checkRedacts("git clone https://ghp_abcdefghijklmnopqrstuvwxyz0123456789@github.com/x", "git clone https://[REDACTED]@github.com/x");
        checkRedacts("OPENAI_API_KEY=sk-proj-abcdefghijklmnopqrstuvwxyz", "OPENAI_API_KEY=[REDACTED]");
        checkRedacts("xoxb-1234567890-abcdefghij", "[REDACTED]");
        checkRedacts("jwt eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.sig_nature-x", "jwt [REDACTED]");
        checkRedacts("psql postgres://admin:s3cret@db:5432/app", "psql postgres://admin:[REDACTED]@db:5432/app");
        checkRedacts("-----BEGIN OPENSSH PRIVATE KEY-----\nb3BlbnNzaC1rZXk\n-----END OPENSSH PRIVATE KEY-----\n$ ", "[REDACTED]\n$ ");
        checkRedacts("-----BEGIN RSA PRIVATE KEY-----\nMIIEow (scrolled off)", "[REDACTED]");
    }

    @Test private static void testNonSecretsAreLeftAlone() {
        checkRedacts("$ pwd\n/home/me", "$ pwd\n/home/me");
        checkRedacts("Password: \n$ ls", "Password: \n$ ls");
        checkRedacts("error: token expected near ';'", "error: token expected near ';'");
        checkRedacts("https://example.com:8080/path", "https://example.com:8080/path");
        Assert.equals(withBuiltInPatterns().redact("password=[REDACTED]").redactionCount(), 0);
        Assert.equals(withBuiltInPatterns().redact("password=a token=b").redactionCount(), 2);
    }

    @Test private static void testUserPatterns() {
        ArrayList<String> warnings = new ArrayList<>();
        List<Pattern> patterns = parseUserPatterns(List.of("# comment", "", "\\bPROJ-[0-9]+\\b", "customer_id=(?<secret>\\d+)", "(unclosed"), "redact-patterns.txt", warnings);
        Assert.equals(patterns.size(), 2);
        Assert.equals(warnings.size(), 1);
        Assert.startsWith(warnings.get(0), "redact-patterns.txt:5: invalid pattern");
        Redactor redactor = new Redactor(patterns);
        Assert.equals(redactor.redact("see PROJ-123 for customer_id=4567").text(), "see [REDACTED] for customer_id=[REDACTED]");
    }
}
