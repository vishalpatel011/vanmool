package com.vanmool.userauthservice.security;

import com.vanmool.userauthservice.repositories.TokenRepository;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.UUID;

// A JWT's signature stays valid after logout, so on its own a protected endpoint would keep accepting it until it
// expires. This check refuses a token whose jti is marked revoked in the token table. Tokens from /oauth2/token have
// no row there (the authorization server keeps its own records) and pass.
@Component
public class RevokedTokenValidator implements OAuth2TokenValidator<Jwt> {
    private static final OAuth2Error REVOKED = new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN, "The token has been revoked", null);

    private final TokenRepository tokenRepository;

    public RevokedTokenValidator(TokenRepository tokenRepository) {
        this.tokenRepository = tokenRepository;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        UUID tokenId = parseUuid(jwt.getId());
        if(tokenId != null && tokenRepository.existsByIdAndRevokedTrue(tokenId)) {
            return OAuth2TokenValidatorResult.failure(REVOKED);
        }
        return OAuth2TokenValidatorResult.success();
    }

    private static UUID parseUuid(String value) {
        if(value == null) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
