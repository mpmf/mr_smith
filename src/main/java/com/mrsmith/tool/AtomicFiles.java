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
        if (Files.exists(target)) {
            replaceAtomically(target, content);
        } else {
            Files.write(target, content);
        }
    }

    private static void replaceAtomically(Path target, byte[] content) throws IOException {
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
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
            }
            throw e;
        }
    }

    private static void preservePermissions(Path target, Path temp) {
        try {
            Files.setPosixFilePermissions(temp, Files.getPosixFilePermissions(target));
        } catch (IOException | UnsupportedOperationException ignored) {
        }
    }
}
