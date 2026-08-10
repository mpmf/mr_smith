package com.mrsmith.tool;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SensitivePathsTest {

    @Test
    void dotEnvIsSensitive() {
        assertTrue(SensitivePaths.isSensitive(Path.of(".env")));
    }

    @Test
    void dotEnvVariantIsSensitive() {
        assertTrue(SensitivePaths.isSensitive(Path.of(".env.local")));
    }

    @Test
    void nestedDotEnvIsSensitive() {
        assertTrue(SensitivePaths.isSensitive(Path.of("config", ".env")));
    }

    @Test
    void sshKeyIsSensitive() {
        assertTrue(SensitivePaths.isSensitive(Path.of("config", "id_rsa")));
    }

    @Test
    void pemIsSensitive() {
        assertTrue(SensitivePaths.isSensitive(Path.of("certs", "server.pem")));
    }

    @Test
    void keyIsSensitive() {
        assertTrue(SensitivePaths.isSensitive(Path.of("a.key")));
    }

    @Test
    void p12IsSensitive() {
        assertTrue(SensitivePaths.isSensitive(Path.of("b.p12")));
    }

    @Test
    void caseInsensitive() {
        assertTrue(SensitivePaths.isSensitive(Path.of("Certs", "SERVER.PEM")));
    }

    @Test
    void normalFileIsNotSensitive() {
        assertFalse(SensitivePaths.isSensitive(Path.of("README.md")));
    }

    @Test
    void javaSourceIsNotSensitive() {
        assertFalse(SensitivePaths.isSensitive(Path.of("src", "Main.java")));
    }

    @Test
    void txtIsNotSensitive() {
        assertFalse(SensitivePaths.isSensitive(Path.of("notes.txt")));
    }
}
