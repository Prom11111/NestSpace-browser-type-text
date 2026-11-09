package com.coke.nestspace.plugin;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/**
 * Host-owned fingerprint derivation shared by Java and Kotlin code.
 */
public final class RuntimeFingerprints {

    private static final String NAMESPACE = "nestspace-runtime-v1";

    private RuntimeFingerprints() {
    }

    public static String sha256(
        String packageName,
        long versionCode,
        String signatureSha256
    ) {
        String input = NAMESPACE + "\n" + packageName + "\n"
            + versionCode + "\n" + signatureSha256;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                result.append(
                    String.format(Locale.ROOT, "%02x", value & 0xff)
                );
            }
            return result.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }
}
