package terminator.llm;

import java.util.*;
import java.util.regex.*;
import org.jessies.test.*;
import terminator.llm.RequestDetector.DetectedRequest;

/**
 * Decides whether an accepted command can be put on the command line, and how. See section 5.3 of the plan.
 *
 * The command is only ever inserted, never run: no newline is ever sent, and commands containing
 * control characters are refused. If the user typed a request ("$ # list big files"), it's erased first.
 */
public final class SuggestionInserter {
    static final int MAX_COMMAND_LENGTH = 1000;

    // A prompt that's waiting for a secret: typing a command there would be invisible, and Enter would submit it.
    private static final Pattern SECRET_PROMPT = Pattern.compile("(?i)(password|passphrase|passcode|\\bpin\\b)[^:]*:\\s*$");

    /**
     * @param eraseCount how many backspaces to send before the command
     * @param command the command to insert
     */
    public record Insertion(int eraseCount, String command) {
    }

    /**
     * Either an insertion or the reason there can't be one.
     */
    public record Decision(Optional<Insertion> insertion, String problem) {
        static Decision insert(int eraseCount, String command) {
            return new Decision(Optional.of(new Insertion(eraseCount, command)), "");
        }

        static Decision refuse(String problem) {
            return new Decision(Optional.empty(), problem);
        }
    }

    private SuggestionInserter() {
    }

    /**
     * @param snapshot the terminal as it was when the suggestion was requested
     * @param request the request detected in that snapshot, if any
     * @param currentCursorLine, currentCursorColumn, currentlyAlternateBuffer the terminal as it is now
     */
    public static Decision decide(String command, TerminalSnapshot snapshot, Optional<DetectedRequest> request, String currentCursorLine, int currentCursorColumn, boolean currentlyAlternateBuffer) {
        String trimmed = command.strip();
        if (trimmed.isEmpty()) {
            return Decision.refuse("the command is empty");
        }
        if (trimmed.codePoints().anyMatch(c -> c < 0x20 || (c >= 0x7f && c <= 0x9f))) {
            return Decision.refuse("the command contains control characters, such as a newline or tab");
        }
        if (trimmed.length() > MAX_COMMAND_LENGTH) {
            return Decision.refuse("the command is too long");
        }
        if (currentlyAlternateBuffer || snapshot.alternateBuffer()) {
            return Decision.refuse("a full-screen application is running");
        }
        if (!currentCursorLine.equals(snapshot.cursorLine()) || currentCursorColumn != snapshot.cursorColumn()) {
            return Decision.refuse("the command line has changed since you asked");
        }
        if (request.isPresent()) {
            return Decision.insert(currentCursorColumn - request.get().startColumn(), trimmed);
        }
        String beforeCursor = currentCursorLine.substring(0, Math.min(currentCursorColumn, currentCursorLine.length())).stripTrailing();
        if (SECRET_PROMPT.matcher(currentCursorLine).find()) {
            return Decision.refuse("the terminal seems to be asking for a password");
        }
        if (!looksLikeEmptyPrompt(beforeCursor, currentCursorLine, currentCursorColumn)) {
            return Decision.refuse("the cursor isn't at an empty prompt");
        }
        return Decision.insert(0, trimmed);
    }

    /**
     * Prompts end in a symbol ("$", "#", "%", ">", "]", ":", "➤", "❯"...) and a space, with the cursor after the space.
     * Text the user has typed usually ends in a letter or digit, or has the cursor straight after it.
     */
    private static boolean looksLikeEmptyPrompt(String beforeCursor, String line, int cursorColumn) {
        if (beforeCursor.isEmpty() || cursorColumn < line.stripTrailing().length()) {
            return false;
        }
        boolean spaceBeforeCursor = cursorColumn > beforeCursor.length();
        int last = beforeCursor.codePointBefore(beforeCursor.length());
        return spaceBeforeCursor && !Character.isLetterOrDigit(last);
    }

    private static TerminalSnapshot snapshot(String cursorLine, int cursorColumn) {
        return TerminalSnapshot.fromLines(List.of(cursorLine), cursorLine, cursorColumn, false, "", 80, 1000);
    }

    private static Decision decide(String command, String line, int column) {
        Optional<DetectedRequest> request = RequestDetector.detect(line, column, List.of("#", "--", "//"));
        return decide(command, snapshot(line, column), request, line, column, false);
    }

    @Test private static void testInsertReplacingRequest() {
        String line = "user@h:~$ # count words in each file";
        Assert.equals(decide("for i in *; do wc -w \"$i\"; done", line, line.length()), Decision.insert(line.length() - 10, "for i in *; do wc -w \"$i\"; done"));
        // Spaces typed after the request are erased too.
        Assert.equals(decide(" ls -la ", "$ # list  ", 10), Decision.insert(8, "ls -la"));
        // Text before the request stays.
        Assert.equals(decide("ls -la /nope", "$ ls -la # why does this fail", 29), Decision.insert(20, "ls -la /nope"));
    }

    @Test private static void testInsertAtEmptyPrompt() {
        for (String prompt : List.of("user@h:~$ ", "root@h:/# ", "zenith 14:37:59 ~/work ➤ ", "mysql> ", ">>> ", "In [3]: ", "[me@box ~]$ ", "❯ ")) {
            Assert.equals(prompt + decide("ls", prompt, prompt.length()), prompt + Decision.insert(0, "ls"));
        }
    }

    @Test private static void testRefusals() {
        checkRefused(decide("ls\nrm -rf /", "$ # x", 5), "control characters");
        checkRefused(decide("ls\t-la", "$ # x", 5), "control characters");
        checkRefused(decide("ls \u001b[31m", "$ # x", 5), "control characters");
        checkRefused(decide("x".repeat(MAX_COMMAND_LENGTH + 1), "$ # x", 5), "too long");
        checkRefused(decide("  ", "$ # x", 5), "empty");

        // Typed text without a request, or the cursor mid-line.
        checkRefused(decide("ls", "$ ls -la", 8), "isn't at an empty prompt");
        checkRefused(decide("ls", "$ ls -la ", 9), "isn't at an empty prompt");
        checkRefused(decide("ls", "$ ", 1), "isn't at an empty prompt");
        checkRefused(decide("ls", "", 0), "isn't at an empty prompt");
        checkRefused(decide("ls", "[sudo] password for me: ", 24), "password");
        checkRefused(decide("ls", "Enter passphrase for key '/home/me/.ssh/id_ed25519': ", 53), "password");

        // The terminal changed while the model was thinking.
        String line = "$ # list big files";
        TerminalSnapshot before = snapshot(line, line.length());
        Optional<DetectedRequest> request = RequestDetector.detect(line, line.length(), List.of("#"));
        checkRefused(decide("du -sh *", before, request, line + "!", line.length() + 1, false), "has changed");
        checkRefused(decide("du -sh *", before, request, "$ ", 2, false), "has changed");
        checkRefused(decide("du -sh *", before, request, line, line.length(), true), "full-screen");
    }

    private static void checkRefused(Decision decision, String expectedProblem) {
        Assert.equals(decision.insertion(), Optional.empty());
        Assert.contains(decision.problem(), expectedProblem);
    }
}
