package terminator.llm;

import java.util.*;
import org.jessies.test.*;
import terminator.model.*;

/**
 * An immutable copy of the terminal text an LLM request needs, so the request can be
 * processed off the event dispatch thread while the terminal carries on changing.
 *
 * @param lines the context lines, oldest first, cleaned up and within the character budget
 * @param cursorLine the full text of the line the cursor is on
 * @param cursorColumn the cursor's column on that line (may be beyond the end of the text)
 * @param alternateBuffer whether a full-screen application (vim, less, top) is using the alternate buffer
 * @param title the terminal's title, often "user@host: cwd" when set by the remote shell
 * @param width the terminal width in columns
 */
public record TerminalSnapshot(List<String> lines, String cursorLine, int cursorColumn, boolean alternateBuffer, String title, int width) {
    /**
     * Captures a snapshot of the given model. Must be called on the event dispatch thread,
     * which is where the model is modified.
     */
    public static TerminalSnapshot capture(TerminalModel model, String title, int characterBudget) {
        if (!java.awt.EventQueue.isDispatchThread()) {
            throw new IllegalStateException("TerminalSnapshot.capture must be called on the event dispatch thread");
        }
        // In the alternate buffer, the lines before the display area are the scrollback of the
        // underlying shell, which isn't what the user is looking at.
        boolean alternateBuffer = model.usingAlternateBuffer();
        int firstLine = alternateBuffer ? Math.max(0, model.getFirstDisplayLine()) : 0;
        ArrayList<String> rawLines = new ArrayList<>();
        for (int i = firstLine; i < model.getLineCount(); ++i) {
            rawLines.add(model.getTextLine(i).getString());
        }
        Location cursor = model.getCursorPosition();
        int cursorLineIndex = cursor.getLineIndex() - firstLine;
        String cursorLine = (cursorLineIndex >= 0 && cursorLineIndex < rawLines.size()) ? rawLines.get(cursorLineIndex) : "";
        return fromLines(rawLines, cursorLine, cursor.getCharOffset(), alternateBuffer, title, model.getWidth(), characterBudget);
    }

    /**
     * Builds a snapshot from raw lines (oldest first), keeping the most recent lines that fit within
     * characterBudget (counting a newline per line). The most recent lines are the visible screen,
     * so they're always kept in preference to scrollback; if the screen alone exceeds the budget,
     * its oldest lines are dropped.
     */
    static TerminalSnapshot fromLines(List<String> rawLines, String cursorLine, int cursorColumn, boolean alternateBuffer, String title, int width, int characterBudget) {
        List<String> cleaned = collapseBlankLines(dropTrailingBlankLines(stripTrailingWhitespace(rawLines)));
        return new TerminalSnapshot(keepMostRecent(cleaned, characterBudget), cursorLine, cursorColumn, alternateBuffer, Objects.requireNonNullElse(title, ""), width);
    }
    
    /**
     * Returns a copy of this snapshot with its lines trimmed to a smaller budget, for when
     * something else (such as the user's context) needs part of the budget.
     */
    public TerminalSnapshot withBudget(int characterBudget) {
        return new TerminalSnapshot(keepMostRecent(lines, characterBudget), cursorLine, cursorColumn, alternateBuffer, title, width);
    }
    
    private static List<String> keepMostRecent(List<String> cleaned, int characterBudget) {
        ArrayDeque<String> kept = new ArrayDeque<>();
        int remaining = characterBudget;
        for (int i = cleaned.size() - 1; i >= 0 && remaining > 0; --i) {
            String line = cleaned.get(i);
            int cost = line.length() + 1;
            if (cost > remaining) {
                if (kept.isEmpty()) {
                    // A single enormous line: keep its end, which is nearest the cursor.
                    kept.addFirst(line.substring(line.length() - (remaining - 1)));
                }
                break;
            }
            kept.addFirst(line);
            remaining -= cost;
        }
        return List.copyOf(kept);
    }

    /**
     * Returns the context lines as a single string.
     */
    public String text() {
        return String.join("\n", lines);
    }

    private static List<String> stripTrailingWhitespace(List<String> lines) {
        return lines.stream().map(String::stripTrailing).toList();
    }

    private static List<String> dropTrailingBlankLines(List<String> lines) {
        int end = lines.size();
        while (end > 0 && lines.get(end - 1).isEmpty()) {
            --end;
        }
        return lines.subList(0, end);
    }

    // Full-screen redraws and "clear" leave long runs of blank lines that are just noise.
    private static List<String> collapseBlankLines(List<String> lines) {
        ArrayList<String> result = new ArrayList<>();
        int blankRun = 0;
        for (String line : lines) {
            blankRun = line.isEmpty() ? blankRun + 1 : 0;
            if (blankRun <= 2) {
                result.add(line);
            }
        }
        return result;
    }

    @Test private static void testCleanUp() {
        List<String> raw = List.of("a  ", "", "", "", "", "b\t", "", "  ");
        TerminalSnapshot snapshot = fromLines(raw, "b", 1, false, null, 80, 1000);
        Assert.equals(snapshot.lines(), List.of("a", "", "", "b"));
        Assert.equals(snapshot.text(), "a\n\n\nb");
        Assert.equals(snapshot.title(), "");
    }

    @Test private static void testBudgetKeepsMostRecentLines() {
        List<String> raw = List.of("oldest", "older", "recent", "$ # help");
        // "recent\n" (7) + "$ # help\n" (9) = 16; "older\n" would need 6 more.
        Assert.equals(fromLines(raw, "$ # help", 8, false, "", 80, 20).lines(), List.of("recent", "$ # help"));
        Assert.equals(fromLines(raw, "$ # help", 8, false, "", 80, 22).lines(), List.of("older", "recent", "$ # help"));
    }

    @Test private static void testWithBudget() {
        TerminalSnapshot snapshot = fromLines(List.of("one", "two", "three"), "three", 5, false, "t", 80, 100);
        Assert.equals(snapshot.withBudget(10).lines(), List.of("two", "three"));
        Assert.equals(snapshot.withBudget(10).title(), "t");
    }
    
    @Test private static void testBudgetSmallerThanOneLine() {
        TerminalSnapshot snapshot = fromLines(List.of("0123456789"), "", 0, false, "", 80, 5);
        Assert.equals(snapshot.lines(), List.of("6789"));
    }
}
