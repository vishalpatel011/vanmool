package com.vanmool.userauthservice.controllers;

import com.vanmool.userauthservice.configs.JwtConfig;
import com.vanmool.userauthservice.configs.SecurityConfig;
import com.vanmool.userauthservice.exceptions.InvalidResetLinkException;
import com.vanmool.userauthservice.repositories.TokenRepository;
import com.vanmool.userauthservice.security.RestSecurityErrorHandler;
import com.vanmool.userauthservice.security.RevokedTokenValidator;
import com.vanmool.userauthservice.services.PasswordResetService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// The reset endpoints with the real security rules: open without a token. PasswordResetService is a mock.
@WebMvcTest(PasswordResetController.class)
@Import({SecurityConfig.class, RestSecurityErrorHandler.class, JwtConfig.class, RevokedTokenValidator.class})
class PasswordResetControllerWebMvcTest {
    // The shape of a real token: 32 bytes (here 0 to 31) in Base64url, 43 characters
    private static final String TOKEN = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PasswordResetService passwordResetService;

    @MockitoBean
    private TokenRepository tokenRepository;

    @Test
    void askingForALink_returns202_withoutATokenAndWithoutSayingWhetherTheAccountExists() throws Exception {
        mockMvc.perform(post("/auth/password-reset").contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"email\": \"asha@example.com\" }"))
                .andExpect(status().isAccepted())
                .andExpect(content().string(""));
        verify(passwordResetService).requestReset("asha@example.com");
    }

    @Test
    void askingWithSomethingThatIsNotAnEmail_returns400() throws Exception {
        mockMvc.perform(post("/auth/password-reset").contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"email\": \"asha\" }"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value("email: must be a well-formed email address"));
        verifyNoInteractions(passwordResetService);
    }

    @Test
    void confirmingWithAValidLink_returns204() throws Exception {
        mockMvc.perform(post("/auth/password-reset/confirm").contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"token\": \"" + TOKEN + "\", \"newPassword\": \"tribal weave pattern\" }"))
                .andExpect(status().isNoContent());
        verify(passwordResetService).resetPassword(TOKEN, "tribal weave pattern");
    }

    @Test
    void confirmingWithAnInvalidLink_returns400_withOneMessageForEveryReason() throws Exception {
        doThrow(new InvalidResetLinkException()).when(passwordResetService).resetPassword(TOKEN, "tribal weave pattern");

        mockMvc.perform(post("/auth/password-reset/confirm").contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"token\": \"" + TOKEN + "\", \"newPassword\": \"tribal weave pattern\" }"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_RESET_LINK"))
                .andExpect(jsonPath("$.message").value("This password reset link is not valid any more; please ask for a new one"));
    }

    @Test
    void confirmingWithAShortPassword_returns400_withoutCallingTheService() throws Exception {
        mockMvc.perform(post("/auth/password-reset/confirm").contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"token\": \"" + TOKEN + "\", \"newPassword\": \"short\" }"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("newPassword: must be at least 8 characters long"));
        verifyNoInteractions(passwordResetService);
    }

    @Test
    void theEmailedLink_opensAFormWithTheToken_andChangesNothingByItself() throws Exception {
        mockMvc.perform(get("/auth/password-reset/confirm").param("token", TOKEN))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(content().string(containsString("<form id=\"reset\">")))
                .andExpect(content().string(containsString("token: \"" + TOKEN + "\"")));
        verifyNoInteractions(passwordResetService);
    }

    @Test
    void aLinkWhoseTokenHasTheWrongShape_isNeverWrittenIntoThePage() throws Exception {
        // Arrange: something that would break out of the script if it were copied into the page
        String injected = "\"});alert(1);//";

        mockMvc.perform(get("/auth/password-reset/confirm").param("token", injected))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("This link is not complete")))
                .andExpect(content().string(not(containsString("alert(1)"))))
                .andExpect(content().string(not(containsString("<form"))));
        mockMvc.perform(get("/auth/password-reset/confirm"))
                .andExpect(content().string(containsString("This link is not complete")));
    }
}
