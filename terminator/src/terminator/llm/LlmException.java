package terminator.llm;

import java.util.*;

/**
 * A failed LLM request, with a message suitable for showing the user.
 * Unchecked so it can pass through CompletableFuture stages.
 */
public class LlmException extends RuntimeException {
    private final OptionalInt httpStatus;

    public LlmException(String message) {
        this(message, OptionalInt.empty(), null);
    }

    public LlmException(String message, Throwable cause) {
        this(message, OptionalInt.empty(), cause);
    }

    public LlmException(String message, OptionalInt httpStatus, Throwable cause) {
        super(message, cause);
        this.httpStatus = httpStatus;
    }

    public OptionalInt httpStatus() {
        return httpStatus;
    }
}
