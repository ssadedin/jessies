package terminator.llm;

import e.forms.*;
import e.gui.*;
import e.ptextarea.*;
import e.util.*;
import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.swing.*;
import terminator.*;
import terminator.model.*;
import terminator.view.*;

/**
 * Runs LLM suggestions for one terminal pane: snapshot on the event dispatch thread, prompt preparation
 * and the request in the background, and progress in the overlay. At most one request per pane is in
 * flight; starting another, pressing Esc, or typing cancels it. See section 3 of the plan.
 */
public final class LlmSuggestController {
    /**
     * The Suggest shortcut: Ctrl+Cmd+L on Mac OS, Ctrl+Shift+L elsewhere.
     */
    public static final KeyStroke SUGGEST_KEY_STROKE;
    // Kept separately because KeyStroke.getModifiers() also includes the legacy modifier bits.
    private static final int SUGGEST_MODIFIERS = InputEvent.CTRL_DOWN_MASK | (GuiUtilities.isMacOs() ? InputEvent.META_DOWN_MASK : InputEvent.SHIFT_DOWN_MASK);
    private static final int MODIFIER_MASK = InputEvent.CTRL_DOWN_MASK | InputEvent.META_DOWN_MASK | InputEvent.ALT_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK;
    static {
        SUGGEST_KEY_STROKE = KeyStroke.getKeyStroke(KeyEvent.VK_L, SUGGEST_MODIFIERS);
    }

    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "LLM request");
        thread.setDaemon(true);
        return thread;
    });

    // Reused while the settings that affect the connection are unchanged. Guarded by LlmSuggestController.class.
    private static OpenAiClient cachedClient;
    private static List<Object> cachedClientKey;

    private final JTerminalPane pane;
    private final SuggestionOverlay overlay = new SuggestionOverlay();

    // Incremented whenever a request starts or is abandoned, so late results from an old request are ignored.
    // Read from background threads; everything else here is only touched on the event dispatch thread.
    private final AtomicInteger generation = new AtomicInteger();
    private OpenAiClient.ChatCall currentCall;
    private javax.swing.Timer progressTimer;
    private Runnable deliverProgress;
    private boolean swallowEscapeKeyTyped = false;

    // The finished suggestion being shown, and what's needed to insert it safely.
    private SuggestionReply shownReply;
    private TerminalSnapshot shownSnapshot;
    private Optional<RequestDetector.DetectedRequest> shownRequest = Optional.empty();

    public LlmSuggestController(JTerminalPane pane) {
        this.pane = pane;
    }

    public SuggestionOverlay getOverlay() {
        return overlay;
    }

    /**
     * Starts a suggestion for the pane's current contents. Call on the event dispatch thread.
     */
    public void suggest() {
        LlmSettings settings = LlmSettings.fromPreferences(Terminator.getPreferences());
        if (!settings.enabled()) {
            SimpleDialog.showAlert(pane, "LLM suggestions are turned off", "Nothing is sent anywhere until you turn on \"Enable LLM suggestions\" in the LLM tab of Preferences.");
            return;
        }
        cancel();
        int requestGeneration = generation.incrementAndGet();
        Font font = pane.getTerminalView().getFont();
        if (!settings.isConfigured()) {
            overlay.start("LLM suggestion", font, settings.overlayFontPercent());
            overlay.showError("Not configured", "Set the endpoint and model in the LLM tab of Preferences.");
            return;
        }
        TerminalSnapshot snapshot = captureSnapshot(settings);
        AtomicReference<Optional<RequestDetector.DetectedRequest>> detectedRequest = new AtomicReference<>(Optional.empty());
        overlay.start("Reading the terminal...", font, settings.overlayFontPercent());

        // Streamed text is batched and delivered to the overlay by a timer, rather than flooding the event queue.
        StringBuilder pendingText = new StringBuilder();
        long startNanos = System.nanoTime();
        AtomicReference<String> statusPrefix = new AtomicReference<>("Preparing");
        deliverProgress = () -> {
            String text;
            synchronized (pendingText) {
                text = pendingText.toString();
                pendingText.setLength(0);
            }
            if (!text.isEmpty()) {
                overlay.appendText(text);
            }
            long seconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - startNanos);
            overlay.setStatus(statusPrefix.get() + "... " + seconds + "s");
        };
        progressTimer = new javax.swing.Timer(50, event -> deliverProgress.run());
        progressTimer.start();

        EXECUTOR.execute(() -> {
            try {
                EndpointGuard.check(settings.endpoint(), settings.allowNonLocalEndpoint()).ifPresent(problem -> {
                    throw new LlmException(problem);
                });
                LlmFiles.Loaded files = LlmFiles.load();
                OpenAiClient client = clientFor(settings);

                // Pass 1, unless a request on the cursor line (or the settings) already decide the scenario.
                SuggestionPipeline.Choice choice = SuggestionPipeline.chooseWithoutClassifier(snapshot, settings).orElse(null);
                if (choice == null) {
                    statusPrefix.set("Working out what would help");
                    SuggestionPipeline.PreparedPrompt classification = SuggestionPipeline.prepareClassification(snapshot, settings, files);
                    if (settings.debugLog()) {
                        LlmDebugLog.append("classification request to " + settings.endpoint() + " model " + settings.classifierModel(), describe(classification));
                    }
                    String reply = runToCompletion(client, classification.chatRequest(), requestGeneration);
                    if (reply == null || generation.get() != requestGeneration) {
                        return;
                    }
                    choice = Classifier.interpret(reply, snapshot, files.templates());
                    if (settings.debugLog()) {
                        LlmDebugLog.append("classification response", reply + "\n\nChose " + choice.templateName() + ": " + choice.notes());
                    }
                }

                // Pass 2.
                SuggestionPipeline.PreparedPrompt prompt = SuggestionPipeline.prepare(snapshot, settings, files, choice);
                if (settings.debugLog()) {
                    LlmDebugLog.append("request to " + settings.endpoint() + " model " + settings.model() + " template " + prompt.templateName(), describe(prompt));
                }
                detectedRequest.set(prompt.request());
                statusPrefix.set(SuggestionPipeline.statusFor(choice, files.templates()));
                OpenAiClient.ChatCall call = client.streamChat(prompt.chatRequest(), text -> {
                    synchronized (pendingText) {
                        pendingText.append(text);
                    }
                });
                register(call, requestGeneration);
                call.result().whenComplete((text, failure) -> {
                    if (settings.debugLog()) {
                        LlmDebugLog.append("response", failure == null ? text : String.valueOf(failure));
                    }
                    GuiUtilities.invokeLater(() -> finish(requestGeneration, failure, snapshot, detectedRequest.get()));
                });
            } catch (LlmException | IllegalArgumentException ex) {
                GuiUtilities.invokeLater(() -> finish(requestGeneration, ex, snapshot, Optional.empty()));
            } catch (RuntimeException ex) {
                Log.warn("LLM suggestion failed", ex);
                GuiUtilities.invokeLater(() -> finish(requestGeneration, ex, snapshot, Optional.empty()));
            }
        });
    }

    /**
     * Makes the call the one Esc cancels, or cancels it straight away if the request has been abandoned meanwhile.
     */
    private void register(OpenAiClient.ChatCall call, int requestGeneration) {
        GuiUtilities.invokeLater(() -> {
            if (generation.get() == requestGeneration) {
                currentCall = call;
            } else {
                call.cancel();
            }
        });
    }

    /**
     * Runs a request to completion on the calling (background) thread, returning its text, or null if it was cancelled.
     * 
     * @throws LlmException if it failed
     */
    private String runToCompletion(OpenAiClient client, OpenAiClient.ChatRequest request, int requestGeneration) {
        for (int attempt = 1; ; ++attempt) {
            OpenAiClient.ChatCall call = client.streamChat(request, text -> {});
            register(call, requestGeneration);
            try {
                return call.result().get();
            } catch (CancellationException ex) {
                return null;
            } catch (InterruptedException ex) {
                call.cancel();
                Thread.currentThread().interrupt();
                return null;
            } catch (ExecutionException ex) {
                Throwable cause = ex.getCause();
                if (cause instanceof CancellationException) {
                    return null;
                }
                // Some servers (LM Studio, for one) reject "response_format": {"type": "json_object"}; the prompt asks for JSON anyway.
                if (attempt == 1 && request.responseFormat() != null && cause instanceof LlmException llmException && llmException.httpStatus().orElse(0) == 400) {
                    request = request.withoutResponseFormat();
                    continue;
                }
                throw (cause instanceof LlmException llmException) ? llmException : new LlmException("The request to the LLM endpoint failed: " + cause, cause);
            }
        }
    }

    private void finish(int requestGeneration, Throwable failure, TerminalSnapshot snapshot, Optional<RequestDetector.DetectedRequest> request) {
        if (generation.get() != requestGeneration) {
            return;
        }
        deliverProgress.run();
        stopProgressTimer();
        currentCall = null;

        Throwable cause = (failure instanceof CompletionException && failure.getCause() != null) ? failure.getCause() : failure;
        if (cause instanceof CancellationException) {
            return;
        }
        if (cause != null) {
            overlay.showError("Suggestion failed", (cause instanceof LlmException || cause instanceof IllegalArgumentException) ? cause.getMessage() : cause.toString());
        } else if (overlay.getText().isBlank()) {
            overlay.showError("No suggestion", "The model returned an empty reply.");
        } else {
            SuggestionReply reply = SuggestionReply.parse(overlay.getText());
            overlay.showReply(reply.command().isPresent() ? "Suggested command" : "Suggestion", reply);
            if (reply.command().isPresent()) {
                shownReply = reply;
                shownSnapshot = snapshot;
                shownRequest = request;
                overlay.setHint("Tab to insert \u00b7 Esc to close");
            }
        }
    }

    /**
     * Cancels any request in flight and closes the overlay.
     */
    public void dismiss() {
        cancel();
        overlay.close();
    }

    private void cancel() {
        generation.incrementAndGet();
        shownReply = null;
        shownSnapshot = null;
        shownRequest = Optional.empty();
        stopProgressTimer();
        if (currentCall != null) {
            currentCall.cancel();
            currentCall = null;
        }
    }

    private void stopProgressTimer() {
        if (progressTimer != null) {
            progressTimer.stop();
            progressTimer = null;
        }
    }

    /**
     * Called first by the pane's keyPressed. Returns true if the event has been dealt with.
     * Esc closes the overlay; other keys (apart from modifiers and menu shortcuts) close it and then
     * carry on to the terminal as usual.
     */
    public boolean handleKeyPressed(KeyEvent event) {
        if (!overlay.isVisible()) {
            return false;
        }
        int keyCode = event.getKeyCode();
        if (keyCode == KeyEvent.VK_TAB && (event.getModifiersEx() & MODIFIER_MASK) == 0 && shownReply != null) {
            insertShownCommand();
            event.consume();
            return true;
        }
        if (keyCode == KeyEvent.VK_ESCAPE && (event.getModifiersEx() & MODIFIER_MASK) == 0) {
            dismiss();
            event.consume();
            // On Linux, Esc also produces a KEY_TYPED event, which would otherwise reach the terminal.
            swallowEscapeKeyTyped = true;
            return true;
        }
        boolean isModifier = keyCode == KeyEvent.VK_SHIFT || keyCode == KeyEvent.VK_CONTROL || keyCode == KeyEvent.VK_META || keyCode == KeyEvent.VK_ALT || keyCode == KeyEvent.VK_ALT_GRAPH;
        if (!isModifier && !TerminatorMenuBar.isKeyboardEquivalent(event) && !isSuggestKeyStroke(event)) {
            dismiss();
        }
        return false;
    }

    /**
     * Puts the suggested command on the command line (replacing the typed request, if any) without running it,
     * or copies it and explains why not if that isn't safe. See SuggestionInserter.
     */
    private void insertShownCommand() {
        TerminalModel model = pane.getTerminalView().getModel();
        Location cursor = model.getCursorPosition();
        String currentLine = cursor.getLineIndex() < model.getLineCount() ? model.getTextLine(cursor.getLineIndex()).getString() : "";
        SuggestionInserter.Decision decision = SuggestionInserter.decide(shownReply.command().get(), shownSnapshot, shownRequest, currentLine, cursor.getCharOffset(), model.usingAlternateBuffer());
        if (decision.insertion().isEmpty()) {
            overlay.copyToClipboard();
            overlay.showNotInserted(shownReply, decision.problem());
            return;
        }
        SuggestionInserter.Insertion insertion = decision.insertion().get();
        // Backspace sends DEL, as JTerminalPane does. Bracketed paste (when the shell has turned it on) inserts the command literally.
        String keystrokes = String.valueOf(Ascii.DEL).repeat(insertion.eraseCount()) + model.bracketPaste(insertion.command());
        dismiss();
        pane.getControl().sendUtf8String(keystrokes);
    }

    /**
     * Copies the suggestion being shown, if any, for the Copy action when nothing is selected in the terminal.
     * Returns false if there's no suggestion to copy.
     */
    public boolean copySuggestion() {
        return overlay.copyToClipboard();
    }

    /**
     * Called first by the pane's keyTyped. Returns true if the event should be swallowed: the KEY_TYPED
     * that follows an Esc we handled, or the ^L that the Suggest shortcut would otherwise send on Linux,
     * where Ctrl+Shift+L doesn't count as a Terminator keyboard equivalent.
     */
    public boolean shouldSwallowKeyTyped(KeyEvent event) {
        if (swallowEscapeKeyTyped && event.getKeyChar() == KeyEvent.VK_ESCAPE) {
            swallowEscapeKeyTyped = false;
            return true;
        }
        swallowEscapeKeyTyped = false;
        return (event.getModifiersEx() & MODIFIER_MASK) == SUGGEST_MODIFIERS && (event.getKeyChar() == ('L' - '@') || Character.toUpperCase(event.getKeyChar()) == 'L');
    }

    private static boolean isSuggestKeyStroke(KeyEvent event) {
        return event.getKeyCode() == KeyEvent.VK_L && (event.getModifiersEx() & MODIFIER_MASK) == SUGGEST_MODIFIERS;
    }

    /**
     * Shows exactly what a suggestion would send, without sending it.
     */
    public void previewRequest() {
        LlmSettings settings = LlmSettings.fromPreferences(Terminator.getPreferences());
        TerminalSnapshot snapshot = captureSnapshot(settings);
        EXECUTOR.execute(() -> {
            String details;
            try {
                Optional<String> endpointProblem = EndpointGuard.check(settings.endpoint(), settings.allowNonLocalEndpoint());
                LlmFiles.Loaded files = LlmFiles.load();
                details = "Enabled: " + (settings.enabled() ? "yes" : "no (nothing will be sent)") + "\n"
                        + "Endpoint: " + settings.endpoint() + (endpointProblem.isPresent() ? "\n  BLOCKED: " + endpointProblem.get() : "") + "\n";
                Optional<SuggestionPipeline.Choice> choice = SuggestionPipeline.chooseWithoutClassifier(snapshot, settings);
                if (choice.isPresent()) {
                    details += describe(SuggestionPipeline.prepare(snapshot, settings, files, choice.get()));
                } else {
                    SuggestionPipeline.Choice example = SuggestionPipeline.Choice.explainBecause("Shown as an example: the classifier's reply decides which scenario is really used.");
                    details += "No request was found on the cursor line, so this takes two passes: the classifier chooses a scenario, then that scenario's template writes the answer.\n"
                            + "\n##### PASS 1: CLASSIFICATION #####\n" + describe(SuggestionPipeline.prepareClassification(snapshot, settings, files))
                            + "\n##### PASS 2, IF THE CLASSIFIER CHOOSES \"explain\" #####\n" + describe(SuggestionPipeline.prepare(snapshot, settings, files, example));
                }
            } catch (RuntimeException ex) {
                details = "Couldn't prepare the request: " + ex.getMessage();
            }
            String finalDetails = details;
            GuiUtilities.invokeLater(() -> showPreview(finalDetails));
        });
    }

    private static String describe(SuggestionPipeline.PreparedPrompt prompt) {
        StringBuilder result = new StringBuilder();
        result.append("Model: ").append(prompt.chatRequest().model()).append('\n');
        result.append("Template: ").append(prompt.templateName()).append('\n');
        prompt.request().ifPresent(request -> result.append("Detected request: ").append(request.marker()).append(' ').append(request.text()).append('\n'));
        result.append("Secrets redacted: ").append(prompt.redactionCount()).append('\n');
        for (String warning : prompt.warnings()) {
            result.append("Warning: ").append(warning).append('\n');
        }
        result.append("\n===== System message =====\n").append(prompt.system()).append('\n');
        result.append("\n===== User message =====\n").append(prompt.user()).append('\n');
        return result.toString();
    }

    private void showPreview(String details) {
        Frame frame = (Frame) SwingUtilities.getAncestorOfClass(Frame.class, pane);
        PTextArea textArea = new PTextArea(30, 100);
        textArea.setEditable(false);
        textArea.setText(details);
        textArea.setWrapStyleWord(true);
        textArea.setCaretPosition(0);
        FormBuilder form = new FormBuilder(frame, "LLM Request Preview");
        form.getFormPanel().addWideRow(new JScrollPane(textArea));
        form.getFormDialog().setRememberBounds(false);
        form.showNonModal();
    }

    private TerminalSnapshot captureSnapshot(LlmSettings settings) {
        return TerminalSnapshot.capture(pane.getTerminalView().getModel(), pane.getTerminalName(), settings.contextChars());
    }

    private static synchronized OpenAiClient clientFor(LlmSettings settings) {
        List<Object> key = Arrays.asList(settings.endpoint(), settings.apiKey(), settings.timeout(), settings.allowNonLocalEndpoint());
        if (cachedClient == null || !key.equals(cachedClientKey)) {
            cachedClient = OpenAiClient.forSettings(settings, EXECUTOR);
            cachedClientKey = key;
        }
        return cachedClient;
    }
}
