package terminator.llm;

import com.google.gson.*;
import java.util.*;
import org.jessies.test.*;
import terminator.llm.RequestDetector.DetectedRequest;
import terminator.llm.SuggestionPipeline.Choice;

/**
 * Interprets the pass 1 classifier's reply. See section 6.1 of the plan.
 *
 * Local models don't always manage clean JSON, so the reply is read leniently, and anything unusable
 * falls back to the "explain" scenario rather than failing. A request the classifier says it found on the
 * cursor line is only trusted for insertion if its exact text is verified there (RequestDetector).
 */
public final class Classifier {
    private Classifier() {
    }

    public static Choice interpret(String reply, TerminalSnapshot snapshot, PromptTemplates templates) {
        Optional<JsonObject> json = extractJson(reply);
        if (json.isEmpty()) {
            return Choice.explainBecause("The classifier's reply wasn't a JSON object, so the explain scenario was used: " + abbreviate(reply));
        }
        String scenarioName = string(json.get(), "scenario");
        Optional<PromptTemplates.Scenario> scenario = templates.scenarios().stream().filter(s -> s.name().equals(scenarioName)).findFirst();
        if (scenario.isEmpty()) {
            return Choice.explainBecause("The classifier chose an unknown scenario \"" + scenarioName + "\", so the explain scenario was used.");
        }
        String request = string(json.get(), "request");
        String note = "The classifier chose \"" + scenarioName + "\"" + (request.isEmpty() ? "" : " (" + request + ")") + ".";
        if (!scenarioName.equals(SuggestionPipeline.EXPLICIT_REQUEST_TEMPLATE)) {
            return new Choice(scenario.get().templateName(), Optional.empty(), request, "#", List.of(note));
        }

        String span = string(json.get(), "request_span");
        String marker = string(json.get(), "comment_marker");
        Optional<DetectedRequest> verified = snapshot.alternateBuffer() ? Optional.empty() : RequestDetector.verifyClassifierRequest(snapshot.cursorLine(), snapshot.cursorColumn(), span, marker);
        if (verified.isPresent()) {
            DetectedRequest found = verified.get();
            return new Choice(scenario.get().templateName(), verified, found.text(), found.marker().isEmpty() ? "#" : found.marker(), List.of(note + " The request was found on the cursor line."));
        }
        String requestText = !request.isEmpty() ? request : span;
        if (requestText.isBlank()) {
            return Choice.explainBecause(note + " But it gave no request, so the explain scenario was used.");
        }
        return new Choice(scenario.get().templateName(), Optional.empty(), requestText, marker.isEmpty() ? "#" : marker, List.of(note + " Its request text wasn't found at the end of the cursor line, so a suggested command can be copied but not inserted."));
    }

    /**
     * Returns the JSON object in a reply, tolerating reasoning in <think> tags, code fences and text around it.
     */
    static Optional<JsonObject> extractJson(String reply) {
        String text = reply.replaceAll("(?s)<think>.*?</think>", "");
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start == -1 || end < start) {
            return Optional.empty();
        }
        try {
            JsonElement element = JsonParser.parseString(text.substring(start, end + 1));
            return element.isJsonObject() ? Optional.of(element.getAsJsonObject()) : Optional.empty();
        } catch (JsonParseException ex) {
            return Optional.empty();
        }
    }

    private static String string(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return (element != null && element.isJsonPrimitive()) ? element.getAsString().strip() : "";
    }

    private static String abbreviate(String s) {
        String oneLine = s.strip().replaceAll("\\s+", " ");
        return oneLine.length() <= 200 ? oneLine : oneLine.substring(0, 200) + "...";
    }

    private static final PromptTemplates TEMPLATES = PromptTemplates.fromSources(Map.of(
            "explicit-request", "---\nscenario: explicit-request\n---\n",
            "command-error", "---\nscenario: command-error\n---\n",
            "explain", "---\nscenario: explain\n---\n"));

    private static TerminalSnapshot snapshot(String cursorLine, boolean alternateBuffer) {
        return TerminalSnapshot.fromLines(List.of(cursorLine), cursorLine, cursorLine.length(), alternateBuffer, "", 80, 8000);
    }

    @Test private static void testExtractJson() {
        Assert.equals(extractJson("{\"scenario\": \"explain\"}").map(o -> o.get("scenario").getAsString()), Optional.of("explain"));
        Assert.equals(extractJson("<think>maybe {not this}</think>\n```json\n{\"scenario\": \"command-error\"}\n```").map(o -> o.get("scenario").getAsString()), Optional.of("command-error"));
        Assert.equals(extractJson("Sure! Here's the JSON: {\"scenario\": \"explain\", \"confidence\": 0.4} Hope that helps."), extractJson("{\"scenario\": \"explain\", \"confidence\": 0.4}"));
        Assert.equals(extractJson("I think it's an error."), Optional.empty());
        Assert.equals(extractJson("{\"scenario\": "), Optional.empty());
        Assert.equals(extractJson("[1, 2]"), Optional.empty());
    }

    @Test private static void testScenarioChoices() {
        TerminalSnapshot shell = snapshot("$ ", false);
        Choice choice = interpret("{\"scenario\": \"command-error\", \"request\": \"Fix the missing Makefile\", \"request_span\": \"\", \"comment_marker\": \"\", \"confidence\": 0.9}", shell, TEMPLATES);
        Assert.equals(choice.templateName(), "command-error");
        Assert.equals(choice.requestText(), "Fix the missing Makefile");
        Assert.equals(choice.insertableRequest(), Optional.empty());

        Assert.equals(interpret("no idea", shell, TEMPLATES).templateName(), "explain");
        Assert.startsWith(interpret("no idea", shell, TEMPLATES).notes().get(0), "The classifier's reply wasn't a JSON object");
        Assert.equals(interpret("{\"scenario\": \"log-error\"}", shell, TEMPLATES).templateName(), "explain");
        Assert.contains(interpret("{\"scenario\": \"log-error\"}", shell, TEMPLATES).notes().get(0), "unknown scenario \"log-error\"");
        Assert.equals(interpret("{\"scenario\": 7}", shell, TEMPLATES).templateName(), "explain");
    }

    @Test private static void testRescuedRequests() {
        // A request with a marker that isn't in the preferences, verified on the cursor line: insertable.
        TerminalSnapshot powershell = snapshot("PS C:\\> ' what does this do", false);
        Choice rescued = interpret("{\"scenario\": \"explicit-request\", \"request\": \"Explain the script\", \"request_span\": \"' what does this do\", \"comment_marker\": \"'\"}", powershell, TEMPLATES);
        Assert.equals(rescued.templateName(), "explicit-request");
        Assert.equals(rescued.insertableRequest(), Optional.of(new DetectedRequest("'", "what does this do", 8, 27)));
        Assert.equals(rescued.requestText(), "what does this do");
        Assert.equals(rescued.commentMarker(), "'");

        // Plain words with no marker.
        Choice plain = interpret("{\"scenario\": \"explicit-request\", \"request\": \"find large files\", \"request_span\": \"find files over 1G\", \"comment_marker\": \"\"}", snapshot("$ find files over 1G", false), TEMPLATES);
        Assert.equals(plain.insertableRequest().map(DetectedRequest::startColumn), Optional.of(2));
        Assert.equals(plain.commentMarker(), "#");

        // The span isn't really there (a paraphrase): still answered, but not insertable.
        Choice paraphrased = interpret("{\"scenario\": \"explicit-request\", \"request\": \"Explain the script\", \"request_span\": \"' what is this script\", \"comment_marker\": \"'\"}", powershell, TEMPLATES);
        Assert.equals(paraphrased.templateName(), "explicit-request");
        Assert.equals(paraphrased.insertableRequest(), Optional.empty());
        Assert.equals(paraphrased.requestText(), "Explain the script");
        Assert.contains(paraphrased.notes().get(0), "can be copied but not inserted");

        // In a full-screen application nothing is insertable, even if the span matches.
        Choice inVim = interpret("{\"scenario\": \"explicit-request\", \"request\": \"x\", \"request_span\": \"' what does this do\", \"comment_marker\": \"'\"}", snapshot("PS C:\\> ' what does this do", true), TEMPLATES);
        Assert.equals(inVim.insertableRequest(), Optional.empty());

        // No request at all.
        Assert.equals(interpret("{\"scenario\": \"explicit-request\"}", snapshot("$ ", false), TEMPLATES).templateName(), "explain");
    }
}
