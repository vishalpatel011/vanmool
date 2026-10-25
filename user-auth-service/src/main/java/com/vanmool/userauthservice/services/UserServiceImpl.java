package com.vanmool.userauthservice.services;

import com.vanmool.userauthservice.exceptions.EmailAlreadyRegisteredException;
import com.vanmool.userauthservice.exceptions.InvalidCredentialsException;
import com.vanmool.userauthservice.exceptions.InvalidTokenException;
import com.vanmool.userauthservice.models.Role;
import com.vanmool.userauthservice.models.Token;
import com.vanmool.userauthservice.models.User;
import com.vanmool.userauthservice.repositories.TokenRepository;
import com.vanmool.userauthservice.repositories.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Service
public class UserServiceImpl implements UserService {
    // A BCrypt hash (cost 12) of a random value nobody knows. Login checks the password against it when the email is
    // unknown, so that case takes as long as a wrong password and response times do not reveal which emails exist
    private static final String UNKNOWN_USER_HASH = "$2a$12$rZSmHB3WppxfABBsK6.JLeCgmD/65w2.y2lpfo4UZYQCTRv2mqtE2";

    private final UserRepository userRepository;
    private final TokenRepository tokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final Clock clock;
    private final Duration tokenValidity;

    public UserServiceImpl(UserRepository userRepository, TokenRepository tokenRepository, PasswordEncoder passwordEncoder,
                           JwtService jwtService, Clock clock, @Value("${auth.token-validity}") Duration tokenValidity) {
        this.userRepository = userRepository;
        this.tokenRepository = tokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.clock = clock;
        this.tokenValidity = tokenValidity;
    }

    @Override
    public User signup(String name, String email, String password) {
        String normalizedEmail = normalizeEmail(email);
        if(userRepository.existsByEmail(normalizedEmail)) {
            throw new EmailAlreadyRegisteredException(normalizedEmail);
        }

        User user = new User();
        user.setName(name.trim());
        user.setEmail(normalizedEmail);
        // Only the BCrypt hash is stored; it contains its own random salt
        user.setPasswordHash(passwordEncoder.encode(password));
        user.getRoles().add(Role.CUSTOMER);
        try {
            return userRepository.save(user);
        } catch (DataIntegrityViolationException e) {
            // Another request registered the same email after our check; the unique constraint stopped the duplicate
            throw new EmailAlreadyRegisteredException(normalizedEmail);
        }
    }

    // One transaction: if creating the JWT fails after the token row is saved, the row is rolled back
    @Override
    @Transactional
    public IssuedToken login(String email, String password) {
        Optional<User> userOptional = userRepository.findByEmail(normalizeEmail(email));
        if(userOptional.isEmpty()) {
            passwordEncoder.matches(password, UNKNOWN_USER_HASH);
            throw new InvalidCredentialsException();
        }
        User user = userOptional.get();
        if(!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new InvalidCredentialsException();
        }

        // The token row is saved first: its generated id becomes the JWT's "jti", which logout uses to revoke it.
        // Whole seconds, because that is all a JWT's iat and exp can hold
        Instant issuedAt = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        Token token = new Token();
        token.setUser(user);
        token.setExpiresAt(issuedAt.plus(tokenValidity));
        Token savedToken = tokenRepository.save(token);

        String jwt = jwtService.issue(user, savedToken.getId(), issuedAt, savedToken.getExpiresAt());
        return new IssuedToken(jwt, savedToken.getExpiresAt());
    }

    @Override
    public void logout(String jwt) {
        // Logging out an invalid, expired or already revoked token is not an error: either way the token is unusable
        UUID tokenId;
        try {
            tokenId = tokenIdOf(jwt);
        } catch (InvalidTokenException e) {
            return;
        }
        tokenRepository.findById(tokenId).ifPresent(token -> {
            token.setRevoked(true);
            tokenRepository.save(token);
        });
    }

    @Override
    public User validateToken(String jwt) {
        // The signature, issuer and expiry are checked first, without the database; then the token's row, so a
        // token ended by logout is refused even though its signature is still good
        UUID tokenId = tokenIdOf(jwt);
        return tokenRepository.findWithUserById(tokenId)
                .filter(token -> !token.isRevoked())
                .map(Token::getUser)
                .orElseThrow(InvalidTokenException::new);
    }

    private static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private UUID tokenIdOf(String jwt) {
        String jti = jwtService.verify(jwt).getId();
        // A genuine signature but no usable jti would not be a token this service handed out
        if(jti == null) {
            throw new InvalidTokenException();
        }
        try {
            return UUID.fromString(jti);
        } catch (IllegalArgumentException e) {
            throw new InvalidTokenException();
        }
    }
}
