package terminator.llm;

import java.util.*;
import java.util.regex.*;
import org.jessies.test.*;

/**
 * A model's reply, interpreted using the output convention in section 6.2 of the plan: a reply whose
 * first line is "COMMAND: <command>" is a command suggestion; anything else is plain text.
 *
 * @param command the suggested command, if the reply is one
 * @param explanation any text after the command line (models don't always stop where they're told to),
 *        or the whole reply if it isn't a command
 */
public record SuggestionReply(Optional<String> command, String explanation) {
    private static final Pattern COMMAND_LINE = Pattern.compile("(?i)^\\s*command:\\s*(.*?)\\s*$");
    private static final Pattern FENCED = Pattern.compile("^```[\\w-]*\\n(.*)\\n```$", Pattern.DOTALL);

    public static SuggestionReply parse(String reply) {
        String text = reply.replace("\r\n", "\n").strip();
        // Some models wrap the whole reply in a code fence despite being asked not to.
        Matcher fenced = FENCED.matcher(text);
        if (fenced.matches()) {
            text = fenced.group(1).strip();
        }
        int newline = text.indexOf('\n');
        String firstLine = (newline == -1) ? text : text.substring(0, newline);
        Matcher matcher = COMMAND_LINE.matcher(firstLine);
        if (matcher.matches()) {
            String command = stripInlineCode(matcher.group(1));
            if (!command.isEmpty()) {
                return new SuggestionReply(Optional.of(command), (newline == -1) ? "" : text.substring(newline + 1).strip());
            }
        }
        return new SuggestionReply(Optional.empty(), text);
    }

    /**
     * Returns what to show the user: the command on its own line, followed by any explanation.
     */
    public String displayText() {
        return command.map(c -> explanation.isEmpty() ? c : c + "\n\n" + explanation).orElse(explanation);
    }

    /**
     * Returns what Copy should copy: just the command if there is one, since that's what the user will run.
     */
    public String copyText() {
        return command.orElse(explanation);
    }

    private static String stripInlineCode(String command) {
        if (command.length() >= 2 && command.startsWith("`") && command.endsWith("`") && command.indexOf('`', 1) == command.length() - 1) {
            return command.substring(1, command.length() - 1).strip();
        }
        return command;
    }

    @Test private static void testParse() {
        SuggestionReply reply = parse("COMMAND: for i in *; do printf \"%-30s | %s\\n\" \"$i\" \"$(wc -w < \"$i\")\"; done\n");
        Assert.equals(reply.command(), Optional.of("for i in *; do printf \"%-30s | %s\\n\" \"$i\" \"$(wc -w < \"$i\")\"; done"));
        Assert.equals(reply.copyText(), reply.command().get());
        Assert.equals(reply.displayText(), reply.command().get());

        Assert.equals(parse("  command:   `du -sh * | sort -h`  ").command(), Optional.of("du -sh * | sort -h"));
        Assert.equals(parse("```\nCOMMAND: ls -la\n```").command(), Optional.of("ls -la"));
        // Backticks inside a command are left alone.
        Assert.equals(parse("COMMAND: `a` && `b`").command(), Optional.of("`a` && `b`"));

        SuggestionReply withExplanation = parse("COMMAND: make -f build/Makefile\r\n\r\nThere's no Makefile here.");
        Assert.equals(withExplanation.command(), Optional.of("make -f build/Makefile"));
        Assert.equals(withExplanation.explanation(), "There's no Makefile here.");
        Assert.equals(withExplanation.copyText(), "make -f build/Makefile");
        Assert.equals(withExplanation.displayText(), "make -f build/Makefile\n\nThere's no Makefile here.");
    }

    @Test private static void testPlainText() {
        SuggestionReply reply = parse("There's no Makefile here.\nTry:\nmake -f build/Makefile\n");
        Assert.equals(reply.command(), Optional.empty());
        Assert.equals(reply.copyText(), "There's no Makefile here.\nTry:\nmake -f build/Makefile");
        // A COMMAND: line that isn't first doesn't make it a command reply, and neither does an empty one.
        Assert.equals(parse("Here you go:\nCOMMAND: ls").command(), Optional.empty());
        Assert.equals(parse("COMMAND:").command(), Optional.empty());
    }
}
