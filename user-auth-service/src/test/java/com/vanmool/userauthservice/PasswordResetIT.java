package com.vanmool.userauthservice;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// The whole reset flow over real HTTP against the MySQL in DB_URL. The emailed link is read from the outbox, where the
// PasswordResetRequested event waits for Kafka, as notification-service would receive it. Cleans up its own rows.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PasswordResetIT {
    private static final String LINK_PREFIX = "/auth/password-reset/confirm?token=";

    private final String email = "it-reset-" + UUID.randomUUID() + "@example.com";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void removeTheTestUser() {
        jdbcTemplate.update("DELETE FROM outbox_event WHERE JSON_UNQUOTE(payload->'$.email') = ?", email);
        jdbcTemplate.update("DELETE p FROM password_reset_token p JOIN users u ON u.id = p.user_id WHERE u.email = ?", email);
        jdbcTemplate.update("DELETE t FROM token t JOIN users u ON u.id = t.user_id WHERE u.email = ?", email);
        jdbcTemplate.update("DELETE r FROM user_role r JOIN users u ON u.id = r.user_id WHERE u.email = ?", email);
        jdbcTemplate.update("DELETE FROM users WHERE email = ?", email);
    }

    private HttpStatus askForALink(String address) {
        return (HttpStatus) restTemplate.postForEntity("/auth/password-reset", Map.of("email", address), Void.class).getStatusCode();
    }

    // The tokens of the links emailed so far, oldest first
    private List<String> emailedTokens() {
        return jdbcTemplate.queryForList("""
                SELECT JSON_UNQUOTE(payload->'$.resetLink') FROM outbox_event
                WHERE event_type = 'PasswordResetRequested' AND JSON_UNQUOTE(payload->'$.email') = ? ORDER BY created_at""",
                String.class, email).stream().map(link -> link.substring(link.indexOf(LINK_PREFIX) + LINK_PREFIX.length())).toList();
    }

    private ResponseEntity<Map> confirm(String token, String newPassword) {
        return restTemplate.postForEntity("/auth/password-reset/confirm",
                Map.of("token", token, "newPassword", newPassword), Map.class);
    }

    private ResponseEntity<Map> login(String password) {
        return restTemplate.postForEntity("/auth/login", Map.of("email", email, "password", password), Map.class);
    }

    private HttpStatus me(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return (HttpStatus) restTemplate.exchange("/users/me", HttpMethod.GET, new HttpEntity<>(headers), Map.class).getStatusCode();
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void forgottenPassword_isResetThroughTheEmailedLink_once() throws Exception {
        // Arrange: an account signed in on two devices
        restTemplate.postForEntity("/auth/signup",
                Map.of("name", "Reset Test", "email", email, "password", "correct horse battery"), Map.class);
        String phone = (String) login("correct horse battery").getBody().get("token");
        String laptop = (String) login("correct horse battery").getBody().get("token");

        // Ask twice (the first email seemed lost), and once for an address without an account: the same answer
        assertEquals(HttpStatus.ACCEPTED, askForALink(email.toUpperCase()));
        assertEquals(HttpStatus.ACCEPTED, askForALink(email));
        assertEquals(HttpStatus.ACCEPTED, askForALink("nobody-" + UUID.randomUUID() + "@example.com"));
        List<String> tokens = emailedTokens();
        assertEquals(2, tokens.size());

        // Only the hash of each token is stored
        List<String> hashes = jdbcTemplate.queryForList("""
                SELECT p.token_hash FROM password_reset_token p JOIN users u ON u.id = p.user_id
                WHERE u.email = ? ORDER BY p.created_at""", String.class, email);
        assertEquals(List.of(sha256(tokens.get(0)), sha256(tokens.get(1))), hashes);

        // Opening the link (as a mail scanner would) shows the form and uses nothing up
        ResponseEntity<String> page = restTemplate.getForEntity(LINK_PREFIX + tokens.get(0), String.class);
        assertEquals(HttpStatus.OK, page.getStatusCode());
        assertTrue(page.getBody().contains("<form id=\"reset\">"));
        assertNull(jdbcTemplate.queryForObject("SELECT used_at FROM password_reset_token WHERE token_hash = ?",
                java.sql.Timestamp.class, hashes.get(0)));

        // Reset with the first link
        assertEquals(HttpStatus.NO_CONTENT, confirm(tokens.get(0), "tribal weave pattern").getStatusCode());

        // The new password works, the old does not; every session has ended
        assertEquals(HttpStatus.OK, login("tribal weave pattern").getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, login("correct horse battery").getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, me(phone));
        assertEquals(HttpStatus.UNAUTHORIZED, me(laptop));

        // Neither link works again: the first is used, the second was closed with it
        ResponseEntity<Map> again = confirm(tokens.get(0), "another new password");
        assertEquals(HttpStatus.BAD_REQUEST, again.getStatusCode());
        assertEquals("INVALID_RESET_LINK", again.getBody().get("error"));
        assertEquals(HttpStatus.BAD_REQUEST, confirm(tokens.get(1), "another new password").getStatusCode());
        assertEquals(HttpStatus.OK, login("tribal weave pattern").getStatusCode());

        // The security notice is on its way
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE event_type = 'PasswordChanged' AND JSON_UNQUOTE(payload->'$.email') = ?",
                Integer.class, email));

        // At most three links an hour: the third is sent, the fourth is not (the answer stays 202)
        assertEquals(HttpStatus.ACCEPTED, askForALink(email));
        assertEquals(HttpStatus.ACCEPTED, askForALink(email));
        assertEquals(3, emailedTokens().size());
    }
}
