package terminator.llm;

import e.util.*;
import java.time.Duration;
import java.util.*;
import org.jessies.test.*;
import static terminator.TerminatorPreferences.*;

/**
 * An immutable, typed snapshot of the LLM preferences, taken when a request starts
 * so that a request is unaffected by preference changes while it's in flight.
 */
public record LlmSettings(
        boolean enabled,
        String endpoint,
        String model,
        String classifierModel,
        Optional<String> apiKey,
        int contextChars,
        boolean skipClassification,
        boolean allowNonLocalEndpoint,
        boolean redactSecrets,
        List<String> commentMarkers,
        Duration timeout,
        int overlayFontPercent,
        boolean debugLog) {

    public static LlmSettings fromPreferences(PreferenceGetter preferences) {
        return fromPreferences(preferences, System.getenv());
    }

    static LlmSettings fromPreferences(PreferenceGetter preferences, Map<String, String> environment) {
        String model = preferences.getString(LLM_MODEL).trim();
        String classifierModel = preferences.getString(LLM_CLASSIFIER_MODEL).trim();
        return new LlmSettings(
                preferences.getBoolean(LLM_ENABLED),
                preferences.getString(LLM_ENDPOINT).trim(),
                model,
                classifierModel.isEmpty() ? model : classifierModel,
                apiKeyFromEnvironment(preferences.getString(LLM_API_KEY_ENV_VAR).trim(), environment),
                preferences.getInt(LLM_CONTEXT_CHARS),
                preferences.getBoolean(LLM_SKIP_CLASSIFICATION),
                preferences.getBoolean(LLM_ALLOW_NON_LOCAL_ENDPOINT),
                preferences.getBoolean(LLM_REDACT_SECRETS),
                parseCommentMarkers(preferences.getString(LLM_COMMENT_MARKERS)),
                Duration.ofSeconds(preferences.getInt(LLM_TIMEOUT_SECONDS)),
                preferences.getInt(LLM_OVERLAY_FONT_PERCENT),
                preferences.getBoolean(LLM_DEBUG_LOG));
    }

    /**
     * True if there's enough configuration to attempt a request.
     */
    public boolean isConfigured() {
        return !endpoint.isEmpty() && !model.isEmpty();
    }

    private static Optional<String> apiKeyFromEnvironment(String variableName, Map<String, String> environment) {
        if (variableName.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(environment.get(variableName)).filter(key -> !key.isBlank());
    }

    /**
     * Splits a space-separated marker list, dropping duplicates. The order is kept because it's
     * the priority order RequestDetector uses.
     */
    static List<String> parseCommentMarkers(String markers) {
        LinkedHashSet<String> unique = new LinkedHashSet<>(Arrays.asList(markers.trim().split("\\s+")));
        unique.remove("");
        return List.copyOf(unique);
    }

    @Test private static void testParseCommentMarkers() {
        Assert.equals(parseCommentMarkers("# -- //"), List.of("#", "--", "//"));
        Assert.equals(parseCommentMarkers("  #   '  #  "), List.of("#", "'"));
        Assert.equals(parseCommentMarkers(""), List.of());
    }

    @Test private static void testFromPreferences() {
        HashMap<String, Object> values = new HashMap<>();
        values.put(LLM_ENABLED, true);
        values.put(LLM_ENDPOINT, " http://localhost:11434/v1 ");
        values.put(LLM_MODEL, "qwen3:8b");
        values.put(LLM_CLASSIFIER_MODEL, " ");
        values.put(LLM_API_KEY_ENV_VAR, "MY_KEY");
        values.put(LLM_CONTEXT_CHARS, 8000);
        values.put(LLM_SKIP_CLASSIFICATION, false);
        values.put(LLM_ALLOW_NON_LOCAL_ENDPOINT, false);
        values.put(LLM_REDACT_SECRETS, true);
        values.put(LLM_COMMENT_MARKERS, "# -- //");
        values.put(LLM_TIMEOUT_SECONDS, 120);
        values.put(LLM_OVERLAY_FONT_PERCENT, 85);
        values.put(LLM_DEBUG_LOG, false);
        PreferenceGetter preferences = new PreferenceGetter() {
            @Override public Object get(String key) {
                return values.get(key);
            }
        };

        LlmSettings settings = fromPreferences(preferences, Map.of("MY_KEY", "sekrit"));
        Assert.equals(settings.endpoint(), "http://localhost:11434/v1");
        Assert.equals(settings.classifierModel(), "qwen3:8b");
        Assert.equals(settings.apiKey(), Optional.of("sekrit"));
        Assert.equals(settings.timeout(), Duration.ofMinutes(2));
        Assert.equals(settings.isConfigured(), true);

        Assert.equals(fromPreferences(preferences, Map.of()).apiKey(), Optional.empty());
        Assert.equals(fromPreferences(preferences, Map.of("MY_KEY", " ")).apiKey(), Optional.empty());

        values.put(LLM_CLASSIFIER_MODEL, "qwen3:1.7b");
        values.put(LLM_MODEL, "");
        settings = fromPreferences(preferences, Map.of());
        Assert.equals(settings.classifierModel(), "qwen3:1.7b");
        Assert.equals(settings.isConfigured(), false);
    }
}
