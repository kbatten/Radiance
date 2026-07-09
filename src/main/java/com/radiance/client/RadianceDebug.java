package com.radiance.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Temporary bring-up scaffolding: append flushed one-liners to {@code radiance_render.log} in the game
 * directory so the render-loop path can be traced even under the launcher's console-less javaw.exe
 * (native stderr is discarded, and log4j buffering can lose the last line before a hard native crash).
 * Remove once the render loop is validated.
 */
public final class RadianceDebug {

    private static final Path LOG = Path.of("radiance_render.log");

    private RadianceDebug() {
    }

    public static void log(String line) {
        try {
            Files.writeString(LOG, line + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE);
        } catch (IOException ignored) {
            // best-effort diagnostics only
        }
    }
}
