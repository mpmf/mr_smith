package com.mrsmith.tool;

import java.io.IOException;
import java.nio.file.Path;

final class ToolPaths {

    private ToolPaths() {
    }

    static Path requireWithin(Path root, String pathArg) {
        if (pathArg == null || pathArg.isBlank()) {
            throw new ToolException("missing required path argument");
        }
        Path base = root.toAbsolutePath().normalize();
        Path target = base.resolve(pathArg).normalize();
        if (!target.startsWith(base)) {
            throw new ToolException("path escapes the working directory: " + pathArg);
        }
        return target;
    }

    static Path requireCanonicalWithin(Path root, Path target) {
        try {
            Path baseReal = root.toRealPath();
            Path targetReal = target.toRealPath();
            if (!targetReal.startsWith(baseReal)) {
                throw new ToolException("path resolves outside the working directory: " + target);
            }
            return targetReal;
        } catch (IOException e) {
            throw new ToolException("could not resolve path: " + e.getMessage(), e);
        }
    }
}
