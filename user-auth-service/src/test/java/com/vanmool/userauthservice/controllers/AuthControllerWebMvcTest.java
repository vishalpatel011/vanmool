package com.vanmool.userauthservice.controllers;

import com.vanmool.userauthservice.configs.SecurityConfig;
import com.vanmool.userauthservice.exceptions.EmailAlreadyRegisteredException;
import com.vanmool.userauthservice.models.Role;
import com.vanmool.userauthservice.models.User;
import com.vanmool.userauthservice.services.UserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;
import java.util.stream.Stream;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
