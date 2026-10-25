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
        jdbcTemplate.update("DELETE t FROM token t JOIN users u ON u.id = t.user_id WHERE u.email = ?", email);
        jdbcTemplate.update("DELETE r FROM user_role r JOIN users u ON u.id = r.user_id WHERE u.email = ?", email);
        jdbcTemplate.update("DELETE FROM users WHERE email = ?", email);
    }

    private ResponseEntity<Map> callWithToken(HttpMethod method, String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return restTemplate.exchange(path, method, new HttpEntity<>(headers), Map.class);
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

    private String login() {
        ResponseEntity<Map> login = restTemplate.postForEntity("/auth/login",
                Map.of("email", email, "password", "correct horse battery"), Map.class);
        assertEquals(HttpStatus.OK, login.getStatusCode());
        return (String) login.getBody().get("token");
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
