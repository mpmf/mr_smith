package com.mrsmith.tool;

import java.nio.file.Path;
import java.util.regex.Pattern;

final class SensitivePaths {

    private static final Pattern SENSITIVE = Pattern.compile(
            "(^|.*[/\\\\])\\.env($|[.].*)|"
                    + "(^|.*[/\\\\])(id_rsa|id_dsa|id_ecdsa|id_ed25519)([.]|$)|"
                    + "(^|.*[/\\\\])[^/\\\\]*\\.(pem|key|p12|pfx|jks|keystore)$",
            Pattern.CASE_INSENSITIVE);

    private SensitivePaths() {
    }

    static boolean isSensitive(Path path) {
        return SENSITIVE.matcher(path.toString()).matches();
    }
}
