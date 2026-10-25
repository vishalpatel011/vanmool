package com.vanmool.userauthservice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vanmool.userauthservice.security.models.CustomUserDetails;
import com.vanmool.userauthservice.services.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.util.UriComponentsBuilder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Integration test of the OAuth2 Authorization Server: the whole application against MySQL, with MockMvc sending
// requests through every security filter chain. The person signing in is supplied with user(...), so the test does
// not have to fill in the HTML form; everything after that (code, PKCE, token, protected endpoint) is real.
@SpringBootTest
@AutoConfigureMockMvc
class OAuth2FlowIT {
    private static final String CLIENT_ID = "vanmool-web";
    private static final String REDIRECT_URI = "https://oauth.pstmn.io/v1/callback";

    private final String email = "oauth-it-" + UUID.randomUUID() + "@example.com";
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserService userService;

    @Autowired
    private UserDetailsService userDetailsService;

    @Autowired
    private JwtDecoder jwtDecoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Value("${auth.issuer}")
    private String issuer;

    private CustomUserDetails signedInUser;

    @BeforeEach
    void createUser() {
        userService.signup("OAuth Test", email, "correct horse battery");
        signedInUser = (CustomUserDetails) userDetailsService.loadUserByUsername(email);
    }

    @AfterEach
    void removeUser() {
        jdbcTemplate.update("DELETE r FROM user_role r JOIN users u ON u.id = r.user_id WHERE u.email = ?", email);
        jdbcTemplate.update("DELETE FROM users WHERE email = ?", email);
    }

    // PKCE: the app keeps a random "code verifier" and sends only its SHA-256 (the "code challenge") when asking for a
    // code; to redeem the code it must show the verifier, so a stolen code alone is useless
    private static String newCodeVerifier() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String codeChallenge(String codeVerifier) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    }

    private String authorizationCode(String codeVerifier) throws Exception {
        MvcResult authorize = mockMvc.perform(get("/oauth2/authorize")
                        .queryParam("response_type", "code")
                        .queryParam("client_id", CLIENT_ID)
                        .queryParam("redirect_uri", REDIRECT_URI)
                        .queryParam("scope", "openid profile email")
                        .queryParam("state", "state-123")
                        .queryParam("code_challenge", codeChallenge(codeVerifier))
                        .queryParam("code_challenge_method", "S256")
                        .with(user(signedInUser)))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string(HttpHeaders.LOCATION, startsWith(REDIRECT_URI + "?code=")))
                .andReturn();
        var query = UriComponentsBuilder.fromUriString(authorize.getResponse().getHeader(HttpHeaders.LOCATION)).build().getQueryParams();
        assertEquals("state-123", query.getFirst("state"));
        return query.getFirst("code");
    }

    @Test
    void discoveryDocument_andJwks_arePublic() throws Exception {
        mockMvc.perform(get("/.well-known/openid-configuration"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.issuer").value(issuer))
                .andExpect(jsonPath("$.jwks_uri").value(issuer + "/oauth2/jwks"))
                .andExpect(jsonPath("$.code_challenge_methods_supported[0]").value("S256"));
        mockMvc.perform(get("/oauth2/jwks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keys[0].kty").value("RSA"))
                .andExpect(jsonPath("$.keys[0].alg").value("RS256"))
                .andExpect(jsonPath("$.keys[0].kid").isNotEmpty())
                // only the public part: an RSA private key would show "d"
                .andExpect(jsonPath("$.keys[0].d").doesNotExist());
    }

    @Test
    void browserThatIsNotSignedIn_isSentToTheSignInForm() throws Exception {
        mockMvc.perform(get("/oauth2/authorize")
                        .queryParam("response_type", "code")
                        .queryParam("client_id", CLIENT_ID)
                        .queryParam("redirect_uri", REDIRECT_URI)
                        .queryParam("scope", "openid")
                        .queryParam("code_challenge", codeChallenge(newCodeVerifier()))
                        .queryParam("code_challenge_method", "S256")
                        .accept(MediaType.TEXT_HTML))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string(HttpHeaders.LOCATION, "http://localhost/login"));
    }

    @Test
    void authorizationCodeWithPkce_givesTokensThatTheApiAccepts() throws Exception {
        // Arrange
        String codeVerifier = newCodeVerifier();
        String code = authorizationCode(codeVerifier);

        // Act: the app exchanges the code, proving it with the code verifier (a public client has no secret)
        MvcResult tokenResponse = mockMvc.perform(post("/oauth2/token")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "authorization_code")
                        .param("code", code)
                        .param("redirect_uri", REDIRECT_URI)
                        .param("client_id", CLIENT_ID)
                        .param("code_verifier", codeVerifier))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token_type").value("Bearer"))
                .andReturn();
        JsonNode tokens = objectMapper.readTree(tokenResponse.getResponse().getContentAsString());

        // Assert: the access token carries the same identity claims as a token from POST /auth/login
        Jwt accessToken = jwtDecoder.decode(tokens.get("access_token").asText());
        assertEquals(issuer, accessToken.getIssuer().toString());
        assertEquals(signedInUser.getUserId().toString(), accessToken.getSubject());
        assertEquals(email, accessToken.getClaimAsString("email"));
        assertEquals(List.of("CUSTOMER"), accessToken.getClaimAsStringList("roles"));

        // Assert: OpenID Connect also returns an ID token about the same person
        assertNotNull(tokens.get("id_token"));
        Jwt idToken = jwtDecoder.decode(tokens.get("id_token").asText());
        assertEquals(signedInUser.getUserId().toString(), idToken.getSubject());
        assertTrue(idToken.getAudience().contains(CLIENT_ID));

        // Assert: the protected API accepts the OAuth2 access token
        mockMvc.perform(get("/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.get("access_token").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email));
    }

    @Test
    void codeRedeemedWithTheWrongVerifier_isRefused() throws Exception {
        // Arrange
        String code = authorizationCode(newCodeVerifier());

        // Act & Assert: someone who intercepted the code but not the verifier gets nothing
        mockMvc.perform(post("/oauth2/token")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "authorization_code")
                        .param("code", code)
                        .param("redirect_uri", REDIRECT_URI)
                        .param("client_id", CLIENT_ID)
                        .param("code_verifier", newCodeVerifier()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_grant"));
    }
}
