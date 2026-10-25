package com.vanmool.userauthservice.controllers;

import com.vanmool.userauthservice.configs.SecurityConfig;
import com.vanmool.userauthservice.exceptions.EmailAlreadyRegisteredException;
import com.vanmool.userauthservice.exceptions.InvalidCredentialsException;
import com.vanmool.userauthservice.exceptions.InvalidTokenException;
import com.vanmool.userauthservice.models.Role;
import com.vanmool.userauthservice.models.User;
import com.vanmool.userauthservice.services.IssuedToken;
import com.vanmool.userauthservice.services.UserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Web layer test: only Spring MVC and our SecurityConfig start (the @Import makes the test use the real security
// rules, not Spring Boot's default ones); UserService is a mock.
@WebMvcTest(AuthController.class)
@Import(SecurityConfig.class)
class AuthControllerWebMvcTest {
    private static final UUID USER_ID = UUID.fromString("5b0e8f3c-2a41-4c47-9d0a-6f1c2e7b9a10");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserService userService;

    private static User asha() {
        User user = new User();
        user.setId(USER_ID);
        user.setName("Asha Gamit");
        user.setEmail("asha@example.com");
        user.setPasswordHash("$2a$12$hashed");
        user.getRoles().add(Role.CUSTOMER);
        return user;
    }

    @Nested
    @DisplayName("POST /auth/signup")
    class Signup {
        @Test
        void validBody_returns201WithTheUserAndNeverThePassword() throws Exception {
            // Arrange
            when(userService.signup("Asha Gamit", "asha@example.com", "correct horse battery")).thenReturn(asha());

            // Act & Assert: STRICT, so a password or passwordHash field in the response would fail the test
            mockMvc.perform(post("/auth/signup").contentType(MediaType.APPLICATION_JSON).content("""
                            { "name": "Asha Gamit", "email": "asha@example.com", "password": "correct horse battery" }"""))
                    .andExpect(status().isCreated())
                    .andExpect(content().json("""
                            {
                              "id": "5b0e8f3c-2a41-4c47-9d0a-6f1c2e7b9a10",
                              "name": "Asha Gamit",
                              "email": "asha@example.com",
                              "roles": ["CUSTOMER"]
                            }""", JsonCompareMode.STRICT));
        }

        @Test
        void emailAlreadyRegistered_returns409() throws Exception {
            // Arrange
            when(userService.signup(anyString(), anyString(), anyString()))
                    .thenThrow(new EmailAlreadyRegisteredException("asha@example.com"));

            // Act & Assert
            mockMvc.perform(post("/auth/signup").contentType(MediaType.APPLICATION_JSON).content("""
                            { "name": "Asha Gamit", "email": "asha@example.com", "password": "correct horse battery" }"""))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error").value("EMAIL_ALREADY_REGISTERED"));
        }

        static Stream<Arguments> invalidBodies() {
            return Stream.of(
                    Arguments.of("name missing", "{ \"email\": \"asha@example.com\", \"password\": \"correct horse battery\" }", "name: "),
                    Arguments.of("email not an email", "{ \"name\": \"Asha\", \"email\": \"asha.example.com\", \"password\": \"correct horse battery\" }", "email: "),
                    Arguments.of("password under 8 characters", "{ \"name\": \"Asha\", \"email\": \"asha@example.com\", \"password\": \"short\" }", "password: "),
                    Arguments.of("password missing", "{ \"name\": \"Asha\", \"email\": \"asha@example.com\" }", "password: "),
                    // 25 Gujarati letters: only 25 characters, but 75 bytes in UTF-8, over BCrypt's 72-byte limit
                    Arguments.of("password over 72 bytes", "{ \"name\": \"Asha\", \"email\": \"asha@example.com\", \"password\": \"" + "ક".repeat(25) + "\" }", "password: "));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("invalidBodies")
        void invalidBody_returns400NamingTheFieldWithoutEchoingThePassword(String rule, String body, String field) throws Exception {
            // Act & Assert
            mockMvc.perform(post("/auth/signup").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.message", containsString(field)))
                    .andExpect(content().string(not(containsString("short"))));
            verifyNoInteractions(userService);
        }

        @Test
        void brokenJson_returns400MalformedRequest() throws Exception {
            // Act & Assert
            mockMvc.perform(post("/auth/signup").contentType(MediaType.APPLICATION_JSON).content("{ \"name\": "))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("MALFORMED_REQUEST"));
        }
    }

    @Nested
    @DisplayName("POST /auth/login")
    class Login {
        @Test
        void correctCredentials_returnTheBearerTokenAndItsExpiry() throws Exception {
            // Arrange
            when(userService.login("asha@example.com", "correct horse battery"))
                    .thenReturn(new IssuedToken("q3Xb8K-token-value", Instant.parse("2026-10-25T20:00:00Z")));

            // Act & Assert
            mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
                            { "email": "asha@example.com", "password": "correct horse battery" }"""))
                    .andExpect(status().isOk())
                    .andExpect(content().json("""
                            { "token": "q3Xb8K-token-value", "tokenType": "Bearer", "expiresAt": "2026-10-25T20:00:00Z" }""",
                            JsonCompareMode.STRICT));
        }

        @Test
        void wrongCredentials_return401WithOneMessageForEveryCase() throws Exception {
            // Arrange
            when(userService.login(anyString(), anyString())).thenThrow(new InvalidCredentialsException());

            // Act & Assert
            mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
                            { "email": "asha@example.com", "password": "wrong password" }"""))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error").value("INVALID_CREDENTIALS"))
                    .andExpect(jsonPath("$.message").value("Email or password is incorrect"));
        }

        @Test
        void missingPassword_returns400WithoutCallingTheService() throws Exception {
            // Act & Assert
            mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
                            { "email": "asha@example.com" }"""))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.message", containsString("password: ")));
            verifyNoInteractions(userService);
        }
    }

    @Nested
    @DisplayName("POST /auth/logout")
    class Logout {
        @Test
        void bearerToken_isRevokedAndAnswered204() throws Exception {
            // Act & Assert
            mockMvc.perform(post("/auth/logout").header(HttpHeaders.AUTHORIZATION, "Bearer q3Xb8K-token-value"))
                    .andExpect(status().isNoContent())
                    .andExpect(content().string(""));
            verify(userService).logout("q3Xb8K-token-value");
        }

        @Test
        void withoutAnAuthorizationHeader_returns401() throws Exception {
            // Act & Assert
            mockMvc.perform(post("/auth/logout"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                    .andExpect(jsonPath("$.error").value("INVALID_TOKEN"));
            verifyNoInteractions(userService);
        }
    }

    @Nested
    @DisplayName("GET /auth/validate")
    class Validate {
        @Test
        void validToken_returnsItsUser() throws Exception {
            // Arrange
            when(userService.validateToken("q3Xb8K-token-value")).thenReturn(asha());

            // Act & Assert
            mockMvc.perform(get("/auth/validate").header(HttpHeaders.AUTHORIZATION, "Bearer q3Xb8K-token-value"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(USER_ID.toString()))
                    .andExpect(jsonPath("$.email").value("asha@example.com"))
                    .andExpect(jsonPath("$.roles[0]").value("CUSTOMER"));
        }

        @Test
        void expiredOrRevokedToken_returns401WithTheBearerChallenge() throws Exception {
            // Arrange
            when(userService.validateToken("old-token")).thenThrow(new InvalidTokenException());

            // Act & Assert
            mockMvc.perform(get("/auth/validate").header(HttpHeaders.AUTHORIZATION, "Bearer old-token"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                    .andExpect(jsonPath("$.error").value("INVALID_TOKEN"));
        }

        @ParameterizedTest
        @ValueSource(strings = {"Basic YXNoYTpwYXNzd29yZA==", "Bearer ", "q3Xb8K-token-value"})
        void headerThatIsNotABearerToken_returns401WithoutCallingTheService(String authorization) throws Exception {
            // Act & Assert
            mockMvc.perform(get("/auth/validate").header(HttpHeaders.AUTHORIZATION, authorization))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error").value("INVALID_TOKEN"));
            verifyNoInteractions(userService);
        }
    }

    @Nested
    @DisplayName("Spring Security defaults")
    class SecurityDefaults {
        @Test
        void postWithoutACsrfToken_isAccepted() throws Exception {
            // Arrange
            when(userService.signup(anyString(), anyString(), anyString())).thenReturn(asha());

            // Act & Assert: CSRF protection is off, because the API uses no cookies or browser sessions
            mockMvc.perform(post("/auth/signup").contentType(MediaType.APPLICATION_JSON).content("""
                            { "name": "Asha Gamit", "email": "asha@example.com", "password": "correct horse battery" }"""))
                    .andExpect(status().isCreated());
        }

        @Test
        void defaultLoginPage_isSwitchedOff() throws Exception {
            // Act & Assert: without formLogin().disable(), Spring Security would serve an HTML login form here
            mockMvc.perform(get("/login"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error").value("NOT_FOUND"));
        }
    }
}
