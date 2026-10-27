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

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

// Integration test: the whole application on a random port against the MySQL database in DB_URL, called over real
// HTTP. Nothing is mocked and no test transaction wraps the calls, so it sees what a client sees, including problems
// unit tests cannot show (a lazy collection read after the Hibernate session closed). It cleans up its own rows.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthFlowIT {
    private final String email = "it-" + UUID.randomUUID() + "@example.com";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;


    @AfterEach
    void removeTheTestUser() {
        jdbcTemplate.update("DELETE FROM outbox_event WHERE JSON_UNQUOTE(payload->'$.email') = ?", email);
        // TokenRevoked events name the user by id only
        jdbcTemplate.update("DELETE o FROM outbox_event o JOIN users u ON JSON_UNQUOTE(o.payload->'$.userId') = BIN_TO_UUID(u.id) WHERE u.email = ?", email);
        jdbcTemplate.update("DELETE t FROM token t JOIN users u ON u.id = t.user_id WHERE u.email = ?", email);
        jdbcTemplate.update("DELETE r FROM user_role r JOIN users u ON u.id = r.user_id WHERE u.email = ?", email);
        jdbcTemplate.update("DELETE FROM users WHERE email = ?", email);
    }

    private ResponseEntity<Map> callWithToken(HttpMethod method, String path, String token) {
        return callWithToken(method, path, token, null);
    }

    private ResponseEntity<Map> callWithToken(HttpMethod method, String path, String token, Map<String, String> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return restTemplate.exchange(path, method, new HttpEntity<>(body, headers), Map.class);
    }

    @Test
    void signupLoginValidateLogout_againstTheRealDatabase() {
        // Signup
        ResponseEntity<Map> signup = restTemplate.postForEntity("/auth/signup",
                Map.of("name", "Integration Test", "email", email, "password", "correct horse battery"), Map.class);
        assertEquals(HttpStatus.CREATED, signup.getStatusCode());
        assertEquals(List.of("CUSTOMER"), signup.getBody().get("roles"));

        // The password is stored as a BCrypt hash
        String storedHash = jdbcTemplate.queryForObject("SELECT password_hash FROM users WHERE email = ?", String.class, email);
        assertEquals("$2a$12$", storedHash.substring(0, 7));

        // Login, with the email in another letter case
        ResponseEntity<Map> login = restTemplate.postForEntity("/auth/login",
                Map.of("email", email.toUpperCase(), "password", "correct horse battery"), Map.class);
        assertEquals(HttpStatus.OK, login.getStatusCode());
        String token = (String) login.getBody().get("token");
        assertNotNull(token);

        // The token is accepted by /auth/validate and, as a bearer JWT, by the protected /users/me
        ResponseEntity<Map> validate = callWithToken(HttpMethod.GET, "/auth/validate", token);
        assertEquals(HttpStatus.OK, validate.getStatusCode());
        assertEquals(email, validate.getBody().get("email"));
        ResponseEntity<Map> me = callWithToken(HttpMethod.GET, "/users/me", token);
        assertEquals(HttpStatus.OK, me.getStatusCode());
        assertEquals(email, me.getBody().get("email"));

        // After logout the same token is refused everywhere, although its signature is still valid
        assertEquals(HttpStatus.NO_CONTENT, callWithToken(HttpMethod.POST, "/auth/logout", token).getStatusCode());
        ResponseEntity<Map> afterLogout = callWithToken(HttpMethod.GET, "/auth/validate", token);
        assertEquals(HttpStatus.UNAUTHORIZED, afterLogout.getStatusCode());
        assertEquals("Bearer", afterLogout.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE));
        assertEquals(HttpStatus.UNAUTHORIZED, callWithToken(HttpMethod.GET, "/users/me", token).getStatusCode());

        // Logging out again is not an error
        assertEquals(HttpStatus.NO_CONTENT, callWithToken(HttpMethod.POST, "/auth/logout", token).getStatusCode());

        // The logout was announced once, with the token's id (its jti), for the API gateway to refuse it too
        assertEquals(List.of(jtiOf(token)), revokedTokenIds());
    }

    @Test
    void onlyAnAdmin_canGrantARole() {
        // Arrange: a new user is a CUSTOMER
        ResponseEntity<Map> signup = restTemplate.postForEntity("/auth/signup",
                Map.of("name", "Integration Test", "email", email, "password", "correct horse battery"), Map.class);
        String userId = (String) signup.getBody().get("id");
        String customerToken = login();

        // Act & Assert: a customer may not grant roles, not even to themselves
        ResponseEntity<Map> asCustomer = callWithToken(HttpMethod.PUT, "/users/" + userId + "/roles/SELLER", customerToken);
        assertEquals(HttpStatus.FORBIDDEN, asCustomer.getStatusCode());
        assertEquals("FORBIDDEN", asCustomer.getBody().get("error"));

        // Arrange: the first administrator is made directly in the database; roles are read at login, so log in again
        jdbcTemplate.update("INSERT INTO user_role (user_id, role) SELECT id, 'ADMIN' FROM users WHERE email = ?", email);
        String adminToken = login();

        // Act & Assert: an admin can make a user a SELLER
        ResponseEntity<Map> asAdmin = callWithToken(HttpMethod.PUT, "/users/" + userId + "/roles/SELLER", adminToken);
        assertEquals(HttpStatus.OK, asAdmin.getStatusCode());
        assertEquals(List.of("CUSTOMER", "SELLER", "ADMIN"), asAdmin.getBody().get("roles"));
    }

    // The tokenId of every TokenRevoked event of the test user, in order
    private List<String> revokedTokenIds() {
        return jdbcTemplate.queryForList("""
                SELECT JSON_UNQUOTE(o.payload->'$.tokenId') FROM outbox_event o
                JOIN users u ON JSON_UNQUOTE(o.payload->'$.userId') = BIN_TO_UUID(u.id)
                WHERE o.event_type = 'TokenRevoked' AND u.email = ? ORDER BY o.created_at""", String.class, email);
    }

    // The "jti" claim of a JWT, read from its payload
    private static String jtiOf(String jwt) {
        String payload = new String(java.util.Base64.getUrlDecoder().decode(jwt.split("\\.")[1]), java.nio.charset.StandardCharsets.UTF_8);
        return payload.replaceAll(".*\"jti\":\"([^\"]+)\".*", "$1");
    }

    private String login() {
        return login("correct horse battery");
    }

    private String login(String password) {
        ResponseEntity<Map> login = restTemplate.postForEntity("/auth/login",
                Map.of("email", email, "password", password), Map.class);
        assertEquals(HttpStatus.OK, login.getStatusCode());
        return (String) login.getBody().get("token");
    }

    @Test
    void profileChange_andPasswordChange_whichEndsTheOtherSessions() {
        // Arrange: one account signed in on two devices
        restTemplate.postForEntity("/auth/signup",
                Map.of("name", "Integration Test", "email", email, "password", "correct horse battery"), Map.class);
        String phone = login();
        String laptop = login();
        String oldHash = jdbcTemplate.queryForObject("SELECT password_hash FROM users WHERE email = ?", String.class, email);

        // The name changes (trimmed); the email stays
        ResponseEntity<Map> renamed = callWithToken(HttpMethod.PATCH, "/users/me", phone, Map.of("name", "  Renamed Test "));
        assertEquals(HttpStatus.OK, renamed.getStatusCode());
        assertEquals("Renamed Test", renamed.getBody().get("name"));
        assertEquals(email, renamed.getBody().get("email"));

        // A wrong current password changes nothing
        ResponseEntity<Map> wrong = callWithToken(HttpMethod.PUT, "/users/me/password", phone,
                Map.of("currentPassword", "a guess", "newPassword", "tribal weave pattern"));
        assertEquals(HttpStatus.FORBIDDEN, wrong.getStatusCode());
        assertEquals("CURRENT_PASSWORD_INCORRECT", wrong.getBody().get("error"));
        assertEquals(HttpStatus.OK, callWithToken(HttpMethod.GET, "/users/me", laptop).getStatusCode());

        // Change it from the phone
        ResponseEntity<Map> changed = callWithToken(HttpMethod.PUT, "/users/me/password", phone,
                Map.of("currentPassword", "correct horse battery", "newPassword", "tribal weave pattern"));
        assertEquals(HttpStatus.NO_CONTENT, changed.getStatusCode());

        // The phone stays signed in; the laptop's session has ended
        assertEquals(HttpStatus.OK, callWithToken(HttpMethod.GET, "/users/me", phone).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, callWithToken(HttpMethod.GET, "/users/me", laptop).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, callWithToken(HttpMethod.GET, "/auth/validate", laptop).getStatusCode());

        // Only the new password signs in; a new BCrypt hash is stored
        ResponseEntity<Map> oldLogin = restTemplate.postForEntity("/auth/login",
                Map.of("email", email, "password", "correct horse battery"), Map.class);
        assertEquals(HttpStatus.UNAUTHORIZED, oldLogin.getStatusCode());
        assertNotNull(login("tribal weave pattern"));
        String newHash = jdbcTemplate.queryForObject("SELECT password_hash FROM users WHERE email = ?", String.class, email);
        assertNotEquals(oldHash, newHash);
        assertEquals("$2a$12$", newHash.substring(0, 7));

        // The PasswordChanged event was recorded with the change, without any password in it
        List<String> payloads = jdbcTemplate.queryForList(
                "SELECT CAST(payload AS CHAR) FROM outbox_event WHERE JSON_UNQUOTE(payload->'$.email') = ? AND event_type = 'PasswordChanged'",
                String.class, email);
        assertEquals(1, payloads.size());
        assertFalse(payloads.get(0).contains("tribal") || payloads.get(0).contains("$2a$"), payloads.get(0));

        // Only the laptop's session was ended, and announced
        assertEquals(List.of(jtiOf(laptop)), revokedTokenIds());
    }

    @Test
    void wrongPassword_isRefused() {
        // Arrange
        restTemplate.postForEntity("/auth/signup",
                Map.of("name", "Integration Test", "email", email, "password", "correct horse battery"), Map.class);

        // Act
        ResponseEntity<Map> login = restTemplate.postForEntity("/auth/login",
                Map.of("email", email, "password", "wrong password"), Map.class);

        // Assert
        assertEquals(HttpStatus.UNAUTHORIZED, login.getStatusCode());
        assertEquals("INVALID_CREDENTIALS", login.getBody().get("error"));
    }
}
