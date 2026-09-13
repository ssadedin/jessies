package terminator.llm;

import java.util.*;
import org.jessies.test.*;

/**
 * Finds a request the user has typed as a comment on the cursor line, such as "$ # list big files".
 * See section 5.2 of terminator/doc/llm-suggestions-plan.md.
 */
public final class RequestDetector {
    /**
     * A request found on the cursor line.
     *
     * @param marker the comment marker the user typed, or "" for a request the classifier found without one
     * @param text the request itself, without the marker
     * @param startColumn the column of the start of the marker (or of the text, if there's no marker)
     * @param endColumn the column just after the last non-blank character of the request
     */
    public record DetectedRequest(String marker, String text, int startColumn, int endColumn) {
    }

    private RequestDetector() {
    }

    /**
     * Looks for a comment request on the cursor line using the given markers, which are in priority order.
     *
     * A marker occurrence qualifies if it's at the start of the line or follows whitespace, is followed
     * by non-blank text (with a space in between for "'", which is otherwise too often a quote), and
     * the cursor is at or after the end of the text. At any position the longest matching marker is
     * considered, so "--" isn't mistaken for "-". The first qualifying occurrence of the highest-priority
     * marker wins, so in "git checkout -- file # restore it" the "#" comment beats the command's "--".
     */
    public static Optional<DetectedRequest> detect(String cursorLine, int cursorColumn, List<String> markers) {
        int end = cursorLine.stripTrailing().length();
        if (cursorColumn < end) {
            return Optional.empty();
        }
        List<String> longestFirst = markers.stream().filter(marker -> !marker.isEmpty()).sorted(Comparator.comparingInt(String::length).reversed()).toList();
        HashMap<String, DetectedRequest> firstOccurrences = new HashMap<>();
        for (int i = 0; i < end; ++i) {
            if (i > 0 && !Character.isWhitespace(cursorLine.charAt(i - 1))) {
                continue;
            }
            for (String marker : longestFirst) {
                if (cursorLine.startsWith(marker, i)) {
                    requestAfterMarker(cursorLine, i, marker, end).ifPresent(request -> firstOccurrences.putIfAbsent(marker, request));
                    break;
                }
            }
        }
        return markers.stream().map(firstOccurrences::get).filter(Objects::nonNull).findFirst();
    }

    private static Optional<DetectedRequest> requestAfterMarker(String line, int markerStart, String marker, int end) {
        int textStart = markerStart + marker.length();
        if (marker.equals("'") && (textStart >= end || line.charAt(textStart) != ' ')) {
            return Optional.empty();
        }
        String text = line.substring(textStart, end).strip();
        if (text.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new DetectedRequest(marker, text, markerStart, end));
    }

    /**
     * Checks a request the classifier says it found on the cursor line (section 6.1 of the plan), without
     * trusting anything but the text: requestSpan must appear verbatim at the end of the line, start at the
     * start of the line or after whitespace, and end at or before the cursor. If commentMarker isn't empty,
     * requestSpan must start with it and have non-blank text after it.
     */
    public static Optional<DetectedRequest> verifyClassifierRequest(String cursorLine, int cursorColumn, String requestSpan, String commentMarker) {
        String span = Objects.requireNonNullElse(requestSpan, "").strip();
        String marker = Objects.requireNonNullElse(commentMarker, "").strip();
        int end = cursorLine.stripTrailing().length();
        int start = end - span.length();
        if (span.isEmpty() || start < 0 || cursorColumn < end || !cursorLine.startsWith(span, start)) {
            return Optional.empty();
        }
        if (start > 0 && !Character.isWhitespace(cursorLine.charAt(start - 1))) {
            return Optional.empty();
        }
        if (!span.startsWith(marker)) {
            return Optional.empty();
        }
        String text = span.substring(marker.length()).strip();
        if (text.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new DetectedRequest(marker, text, start, end));
    }

    private static final List<String> DEFAULT_MARKERS = List.of("#", "--", "//");

    private static void checkDetect(String line, List<String> markers, String expectedMarker, String expectedText) {
        Optional<DetectedRequest> request = detect(line, line.length(), markers);
        if (expectedMarker == null) {
            Assert.equals(request, Optional.empty());
            return;
        }
        Assert.equals(request.map(DetectedRequest::marker), Optional.of(expectedMarker));
        Assert.equals(request.map(DetectedRequest::text), Optional.of(expectedText));
        // The columns must cover exactly the marker through the end of the text, since that's what gets erased.
        String span = line.substring(request.get().startColumn(), request.get().endColumn());
        Assert.startsWith(span, expectedMarker);
        Assert.equals(span.endsWith(expectedText), true);
    }

    @Test private static void testDetectPlanExamples() {
        checkDetect("user@h:~$ # list big files", DEFAULT_MARKERS, "#", "list big files");
        checkDetect("root@h:~# # why is disk full", DEFAULT_MARKERS, "#", "why is disk full");
        checkDetect("mysql> -- top 10 tables by size", DEFAULT_MARKERS, "--", "top 10 tables by size");
        checkDetect("> // parse this json", DEFAULT_MARKERS, "//", "parse this json");
        checkDetect("PS C:\\> ' what does this do", DEFAULT_MARKERS, null, null);
        checkDetect("PS C:\\> ' what does this do", List.of("#", "--", "//", "'"), "'", "what does this do");
        checkDetect("$ echo 'foo", List.of("#", "'"), null, null);
        checkDetect("$ curl http://x/y", DEFAULT_MARKERS, null, null);
        checkDetect("$ ls -la # why does this fail", DEFAULT_MARKERS, "#", "why does this fail");
    }

    @Test private static void testDetectEdgeCases() {
        // Priority beats position.
        checkDetect("$ git checkout -- file # restore it", DEFAULT_MARKERS, "#", "restore it");
        checkDetect("$ git checkout -- file # restore it", List.of("--", "#"), "--", "file # restore it");
        // A marker with nothing after it isn't a request.
        checkDetect("$ #", DEFAULT_MARKERS, null, null);
        checkDetect("user@h:~# ", DEFAULT_MARKERS, null, null);
        // "#" straight after text is part of a word, but "#word" after whitespace is a comment (as in bash).
        checkDetect("$ echo a#b", DEFAULT_MARKERS, null, null);
        checkDetect("$ #list", DEFAULT_MARKERS, "#", "list");
        // The longest marker at a position is the one considered.
        checkDetect("SQL> -- count", List.of("-", "--"), "--", "count");
        // The cursor must be at or after the end of the request.
        Assert.equals(detect("$ # help me", 5, DEFAULT_MARKERS), Optional.empty());
        Assert.equals(detect("$ # help me   ", 11, DEFAULT_MARKERS), Optional.of(new DetectedRequest("#", "help me", 2, 11)));
    }

    @Test private static void testVerifyClassifierRequest() {
        String line = "PS C:\\> ' what does this do";
        int cursor = line.length();
        Assert.equals(verifyClassifierRequest(line, cursor, "' what does this do", "'"), Optional.of(new DetectedRequest("'", "what does this do", 8, 27)));
        // Plain words, no marker.
        Assert.equals(verifyClassifierRequest("$ find big files ", 16, "find big files", ""), Optional.of(new DetectedRequest("", "find big files", 2, 16)));
        // Wrong span: not verbatim.
        Assert.equals(verifyClassifierRequest(line, cursor, "' what does it do", "'"), Optional.empty());
        // Span not at the end of the line.
        Assert.equals(verifyClassifierRequest(line, cursor, "' what does", "'"), Optional.empty());
        // Span not at the cursor.
        Assert.equals(verifyClassifierRequest(line, 10, "' what does this do", "'"), Optional.empty());
        // Marker mismatch.
        Assert.equals(verifyClassifierRequest(line, cursor, "' what does this do", "#"), Optional.empty());
        // Span starting mid-word.
        Assert.equals(verifyClassifierRequest(line, cursor, "hat does this do", ""), Optional.empty());
        // Marker with nothing after it.
        Assert.equals(verifyClassifierRequest("SQL> --", 7, "--", "--"), Optional.empty());
        Assert.equals(verifyClassifierRequest(line, cursor, null, null), Optional.empty());
    }
}
