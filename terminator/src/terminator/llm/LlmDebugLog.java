package terminator.llm;

import e.util.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.time.*;
import java.util.*;

/**
 * An opt-in log of LLM requests and responses in ~/.terminator/llm/debug.log, for tuning prompts.
 * It contains terminal contents (after redaction), so it's only readable by the user. See section 7 of the plan.
 */
public final class LlmDebugLog {
    private LlmDebugLog() {
    }

    public static synchronized void append(String heading, String text) {
        Path file = LlmFiles.userDirectory().resolve("debug.log");
        try {
            Files.createDirectories(file.getParent());
            if (!Files.exists(file)) {
                Files.createFile(file);
                try {
                    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
                } catch (UnsupportedOperationException ex) {
                    // Not a POSIX file system.
                }
            }
            String entry = "===== " + OffsetDateTime.now() + " " + heading + " =====\n" + text + "\n\n";
            Files.writeString(file, entry, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        } catch (IOException ex) {
            Log.warn("Couldn't write to the LLM debug log " + file, ex);
        }
    }
}
