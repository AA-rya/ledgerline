package com.ledgerline.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Computes a stable hash of a posting request's business-meaningful
 * content, used to detect idempotency-key reuse with a different
 * payload (see IdempotencyRecord.requestHash). Deliberately hashes a
 * canonical string we build ourselves rather than a generic object
 * serialization, so the hash is stable across JVM/library versions.
 */
public final class RequestHasher {

    private RequestHasher() {}

    public static String sha256Hex(String canonical) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed present on every standard JVM.
            throw new IllegalStateException(e);
        }
    }
}
