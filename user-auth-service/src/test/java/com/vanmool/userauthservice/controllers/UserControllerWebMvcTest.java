package com.vanmool.userauthservice.controllers;

import com.vanmool.userauthservice.configs.JwtConfig;
import com.vanmool.userauthservice.configs.SecurityConfig;
import com.vanmool.userauthservice.exceptions.UserNotFoundException;
import com.vanmool.userauthservice.models.Role;
import com.vanmool.userauthservice.models.User;
import com.vanmool.userauthservice.repositories.TokenRepository;
import com.vanmool.userauthservice.security.RestSecurityErrorHandler;
import com.vanmool.userauthservice.security.RevokedTokenValidator;
import com.vanmool.userauthservice.services.UserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Web layer test with the real security rules. jwt() from spring-security-test stands in for a checked bearer token
// with a chosen subject and roles, so authorization can be tested without creating real tokens; the tests that send
// no token or a bad one go through the real JWT checking.
@WebMvcTest(UserController.class)
@Import({SecurityConfig.class, RestSecurityErrorHandler.class, JwtConfig.class, RevokedTokenValidator.class})
class UserControllerWebMvcTest {
    private static final UUID ASHA_ID = UUID.fromString("5b0e8f3c-2a41-4c47-9d0a-6f1c2e7b9a10");
    private static final UUID PRODUCER_ID = UUID.fromString("9a1d6c2e-0b37-4f58-8e21-7c4b3a5d6e90");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private TokenRepository tokenRepository;

    private static User user(UUID id, String email, Role... roles) {
        User user = new User();
        user.setId(id);
        user.setName("Test User");
        user.setEmail(email);
        for(Role role : roles) {
            user.getRoles().add(role);
        }
        return user;
    }

    private static RequestPostProcessor tokenFor(UUID userId, String... roles) {
        List<GrantedAuthority> authorities = Arrays.stream(roles)
                .<GrantedAuthority>map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                .toList();
        return jwt().jwt(token -> token.subject(userId.toString())).authorities(authorities);
    }

    @Nested
    @DisplayName("GET /users/me")
    class Me {
        @Test
        void validToken_returnsTheUserNamedByItsSubject() throws Exception {
            // Arrange
            when(userService.getUser(ASHA_ID)).thenReturn(user(ASHA_ID, "asha@example.com", Role.CUSTOMER));

            // Act & Assert
            mockMvc.perform(get("/users/me").with(tokenFor(ASHA_ID, "CUSTOMER")))
                    .andExpect(status().isOk())
                    .andExpect(content().json("""
                            {
                              "id": "5b0e8f3c-2a41-4c47-9d0a-6f1c2e7b9a10",
                              "name": "Test User",
                              "email": "asha@example.com",
                              "roles": ["CUSTOMER"]
                            }""", JsonCompareMode.STRICT));
        }

        @Test
        void withoutAToken_returns401InTheServiceErrorFormat() throws Exception {
            // Act & Assert
            mockMvc.perform(get("/users/me"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                    .andExpect(jsonPath("$.error").value("INVALID_TOKEN"))
                    .andExpect(jsonPath("$.path").value("/users/me"));
            verifyNoInteractions(userService);
        }

        @Test
        void withATokenThatIsNotAValidJwt_returns401() throws Exception {
            // Act & Assert: this one goes through the real JwtDecoder, which cannot parse it
            mockMvc.perform(get("/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error").value("INVALID_TOKEN"));
            verifyNoInteractions(userService);
        }

        @Test
        void userDeletedAfterTheTokenWasIssued_returns404() throws Exception {
            // Arrange
            when(userService.getUser(ASHA_ID)).thenThrow(new UserNotFoundException(ASHA_ID));

            // Act & Assert
            mockMvc.perform(get("/users/me").with(tokenFor(ASHA_ID, "CUSTOMER")))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error").value("USER_NOT_FOUND"));
        }
    }

    @Nested
    @DisplayName("PUT /users/{id}/roles/{role}")
    class GrantRole {
        @Test
        void admin_grantsTheRole() throws Exception {
            // Arrange
            when(userService.grantRole(PRODUCER_ID, Role.SELLER))
                    .thenReturn(user(PRODUCER_ID, "producer@example.com", Role.CUSTOMER, Role.SELLER));

            // Act & Assert
            mockMvc.perform(put("/users/{id}/roles/{role}", PRODUCER_ID, "SELLER").with(tokenFor(ASHA_ID, "ADMIN")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.roles[0]").value("CUSTOMER"))
                    .andExpect(jsonPath("$.roles[1]").value("SELLER"));
        }

        @Test
        void customer_isForbiddenAndNothingChanges() throws Exception {
            // Act & Assert: authenticated (valid token) but not authorized (wrong role)
            mockMvc.perform(put("/users/{id}/roles/{role}", PRODUCER_ID, "SELLER").with(tokenFor(ASHA_ID, "CUSTOMER", "SELLER")))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value("FORBIDDEN"));
            verifyNoInteractions(userService);
        }

        @Test
        void withoutAToken_returns401() throws Exception {
            // Act & Assert
            mockMvc.perform(put("/users/{id}/roles/{role}", PRODUCER_ID, "SELLER"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error").value("INVALID_TOKEN"));
            verifyNoInteractions(userService);
        }

        @Test
        void roleThatDoesNotExist_returns400() throws Exception {
            // Act & Assert
            mockMvc.perform(put("/users/{id}/roles/{role}", PRODUCER_ID, "OWNER").with(tokenFor(ASHA_ID, "ADMIN")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("INVALID_PARAMETER"))
                    .andExpect(jsonPath("$.message").value("Invalid value for 'role'"));
            verifyNoInteractions(userService);
        }

        @Test
        void unknownUser_returns404() throws Exception {
            // Arrange
            when(userService.grantRole(PRODUCER_ID, Role.SELLER)).thenThrow(new UserNotFoundException(PRODUCER_ID));

            // Act & Assert
            mockMvc.perform(put("/users/{id}/roles/{role}", PRODUCER_ID, "SELLER").with(tokenFor(ASHA_ID, "ADMIN")))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error").value("USER_NOT_FOUND"));
        }
    }
}
