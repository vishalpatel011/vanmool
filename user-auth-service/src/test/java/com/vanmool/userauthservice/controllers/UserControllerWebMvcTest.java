package com.vanmool.userauthservice.controllers;

import com.vanmool.userauthservice.configs.JwtConfig;
import com.vanmool.userauthservice.configs.SecurityConfig;
import com.vanmool.userauthservice.exceptions.IncorrectPasswordException;
import com.vanmool.userauthservice.exceptions.PasswordUnchangedException;
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
import org.springframework.http.MediaType;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
    private static final UUID TOKEN_ID = UUID.fromString("0f6e2b8a-77d4-4e8a-b1f3-3c2d9a5e4b71");
    private static final String CHANGE_PASSWORD = """
            { "currentPassword": "correct horse battery", "newPassword": "tribal weave pattern" }""";

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
    @DisplayName("PATCH /users/me")
    class UpdateProfile {
        @Test
        void newName_returnsTheWholeProfile() throws Exception {
            // Arrange
            User renamed = user(ASHA_ID, "asha@example.com", Role.CUSTOMER);
            renamed.setName("Asha Vasava");
            when(userService.updateProfile(ASHA_ID, "Asha Vasava")).thenReturn(renamed);

            // Act & Assert
            mockMvc.perform(patch("/users/me").with(tokenFor(ASHA_ID, "CUSTOMER"))
                            .contentType(MediaType.APPLICATION_JSON).content("{ \"name\": \"Asha Vasava\" }"))
                    .andExpect(status().isOk())
                    .andExpect(content().json("""
                            {
                              "id": "5b0e8f3c-2a41-4c47-9d0a-6f1c2e7b9a10",
                              "name": "Asha Vasava",
                              "email": "asha@example.com",
                              "roles": ["CUSTOMER"]
                            }""", JsonCompareMode.STRICT));
        }

        @Test
        void emptyBody_changesNothing() throws Exception {
            when(userService.updateProfile(eq(ASHA_ID), isNull())).thenReturn(user(ASHA_ID, "asha@example.com", Role.CUSTOMER));

            mockMvc.perform(patch("/users/me").with(tokenFor(ASHA_ID, "CUSTOMER"))
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name").value("Test User"));
        }

        @Test
        void blankOrTooLongName_returns400() throws Exception {
            mockMvc.perform(patch("/users/me").with(tokenFor(ASHA_ID, "CUSTOMER"))
                            .contentType(MediaType.APPLICATION_JSON).content("{ \"name\": \"   \" }"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.message").value("name: must not be blank"));
            mockMvc.perform(patch("/users/me").with(tokenFor(ASHA_ID, "CUSTOMER"))
                            .contentType(MediaType.APPLICATION_JSON).content("{ \"name\": \"" + "a".repeat(101) + "\" }"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("name: size must be between 0 and 100"));
            verifyNoInteractions(userService);
        }

        @Test
        void withoutAToken_returns401() throws Exception {
            mockMvc.perform(patch("/users/me").contentType(MediaType.APPLICATION_JSON).content("{ \"name\": \"X\" }"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error").value("INVALID_TOKEN"));
            verifyNoInteractions(userService);
        }
    }

    @Nested
    @DisplayName("PUT /users/me/password")
    class ChangePassword {
        private RequestPostProcessor ashasLoginToken() {
            return jwt().jwt(token -> token.subject(ASHA_ID.toString()).jti(TOKEN_ID.toString()))
                    .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"));
        }

        @Test
        void validRequest_returns204_andNamesTheSessionToKeep() throws Exception {
            mockMvc.perform(put("/users/me/password").with(ashasLoginToken())
                            .contentType(MediaType.APPLICATION_JSON).content(CHANGE_PASSWORD))
                    .andExpect(status().isNoContent());
            verify(userService).changePassword(ASHA_ID, TOKEN_ID, "correct horse battery", "tribal weave pattern");
        }

        @Test
        void tokenWithoutAnId_passesNoSessionToKeep() throws Exception {
            mockMvc.perform(put("/users/me/password").with(tokenFor(ASHA_ID, "CUSTOMER"))
                            .contentType(MediaType.APPLICATION_JSON).content(CHANGE_PASSWORD))
                    .andExpect(status().isNoContent());
            verify(userService).changePassword(eq(ASHA_ID), isNull(), eq("correct horse battery"), eq("tribal weave pattern"));
        }

        @Test
        void wrongCurrentPassword_returns403_notA401ThatWouldSignTheUserOut() throws Exception {
            doThrow(new IncorrectPasswordException()).when(userService)
                    .changePassword(ASHA_ID, TOKEN_ID, "correct horse battery", "tribal weave pattern");

            mockMvc.perform(put("/users/me/password").with(ashasLoginToken())
                            .contentType(MediaType.APPLICATION_JSON).content(CHANGE_PASSWORD))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value("CURRENT_PASSWORD_INCORRECT"))
                    .andExpect(jsonPath("$.message").value("The current password is not correct"));
        }

        @Test
        void sameNewPassword_returns400() throws Exception {
            doThrow(new PasswordUnchangedException()).when(userService)
                    .changePassword(ASHA_ID, TOKEN_ID, "correct horse battery", "tribal weave pattern");

            mockMvc.perform(put("/users/me/password").with(ashasLoginToken())
                            .contentType(MediaType.APPLICATION_JSON).content(CHANGE_PASSWORD))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.message").value("newPassword: must be different from the current password"));
        }

        @Test
        void shortNewPassword_orNoCurrentPassword_returns400() throws Exception {
            mockMvc.perform(put("/users/me/password").with(ashasLoginToken())
                            .contentType(MediaType.APPLICATION_JSON).content("{ \"newPassword\": \"short\" }"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value(
                            "currentPassword: must not be blank; newPassword: must be at least 8 characters long"));
            verifyNoInteractions(userService);
        }

        @Test
        void withoutAToken_returns401() throws Exception {
            mockMvc.perform(put("/users/me/password").contentType(MediaType.APPLICATION_JSON).content(CHANGE_PASSWORD))
                    .andExpect(status().isUnauthorized());
            verifyNoInteractions(userService);
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
