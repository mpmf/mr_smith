package com.mrsmith.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class AtomicFilesTest {

    @TempDir
    Path tempDir;

    @Test
    void writesNewFile() throws IOException {
        Path target = tempDir.resolve("new.txt");
        AtomicFiles.write(target, "hello".getBytes(StandardCharsets.UTF_8));
        assertEquals("hello", Files.readString(target));
    }

    @Test
    void overwritesExistingFile() throws IOException {
        Path target = tempDir.resolve("file.txt");
        Files.writeString(target, "old");
        AtomicFiles.write(target, "new".getBytes(StandardCharsets.UTF_8));
        assertEquals("new", Files.readString(target));
    }

    @Test
    void preservesExistingFilePermissions() throws IOException {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        Path target = tempDir.resolve("script.sh");
        Files.writeString(target, "old");
        Files.setPosixFilePermissions(target,
                Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
        AtomicFiles.write(target, "new".getBytes(StandardCharsets.UTF_8));
        assertTrue(Files.getPosixFilePermissions(target).contains(PosixFilePermission.OWNER_EXECUTE));
    }

    @Test
    void newFileMatchesPlainWritePermissions() throws IOException {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        Path target = tempDir.resolve("created.txt");
        Path control = tempDir.resolve("control.txt");
        AtomicFiles.write(target, "x".getBytes(StandardCharsets.UTF_8));
        Files.write(control, "x".getBytes(StandardCharsets.UTF_8));
        assertEquals(Files.getPosixFilePermissions(control), Files.getPosixFilePermissions(target));
    }
}
