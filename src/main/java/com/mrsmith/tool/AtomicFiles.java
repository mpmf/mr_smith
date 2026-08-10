package com.mrsmith.tool;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class AtomicFiles {

    private AtomicFiles() {
    }

    public static void write(Path target, byte[] content) throws IOException {
        Path parent = target.toAbsolutePath().getParent();
        Path temp = Files.createTempFile(parent, ".mrsmith-", ".tmp");
        try {
            Files.write(temp, content);
            preservePermissions(target, temp);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(temp);
            throw e;
        }
    }

    private static void preservePermissions(Path target, Path temp) {
        if (!Files.exists(target)) {
            return;
        }
        try {
            Files.setPosixFilePermissions(temp, Files.getPosixFilePermissions(target));
        } catch (IOException | UnsupportedOperationException ignored) {
        }
    }
}
