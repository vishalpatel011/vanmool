package com.vanmool.paymentservice.gateways;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;

// HMAC-SHA256 as hex, the scheme webhooks are signed with: only someone who knows the shared secret can produce the
// signature of a given body
final class HmacSha256 {
    private HmacSha256() {
    }

    static String hex(String secret, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    // Compares in constant time, so the time taken does not reveal how much of a forged signature was right
    static boolean matches(String expectedHex, String actualHex) {
        return actualHex != null && MessageDigest.isEqual(expectedHex.getBytes(StandardCharsets.UTF_8),
                actualHex.getBytes(StandardCharsets.UTF_8));
    }
}
