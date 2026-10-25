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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;

@Service
public class UserServiceImpl implements UserService {
    // A BCrypt hash (cost 12) of a random value nobody knows. Login checks the password against it when the email is
    // unknown, so that case takes as long as a wrong password and response times do not reveal which emails exist
    private static final String UNKNOWN_USER_HASH = "$2a$12$rZSmHB3WppxfABBsK6.JLeCgmD/65w2.y2lpfo4UZYQCTRv2mqtE2";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final TokenRepository tokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;
    private final Duration tokenValidity;

    public UserServiceImpl(UserRepository userRepository, TokenRepository tokenRepository, PasswordEncoder passwordEncoder,
                           Clock clock, @Value("${auth.token-validity}") Duration tokenValidity) {
        this.userRepository = userRepository;
        this.tokenRepository = tokenRepository;
        this.passwordEncoder = passwordEncoder;
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

    @Override
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

        // 32 random bytes (256 bits) from a cryptographic generator, so a token cannot be guessed
        byte[] randomBytes = new byte[32];
        SECURE_RANDOM.nextBytes(randomBytes);
        String tokenValue = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);

        Token token = new Token();
        token.setUser(user);
        token.setTokenHash(sha256(tokenValue));
        token.setExpiresAt(clock.instant().plus(tokenValidity));
        tokenRepository.save(token);
        return new IssuedToken(tokenValue, token.getExpiresAt());
    }

    @Override
    public void logout(String tokenValue) {
        // Logging out an unknown or already revoked token is not an error: the result is the same, the token is unusable
        tokenRepository.findByTokenHash(sha256(tokenValue)).ifPresent(token -> {
            token.setRevoked(true);
            tokenRepository.save(token);
        });
    }

    @Override
    public User validateToken(String tokenValue) {
        return tokenRepository.findByTokenHash(sha256(tokenValue))
                .filter(token -> !token.isRevoked())
                .filter(token -> token.getExpiresAt().isAfter(clock.instant()))
                .map(Token::getUser)
                .orElseThrow(InvalidTokenException::new);
    }

    private static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    // Tokens are long random values, so a plain SHA-256 is enough to store them safely; BCrypt's slowness is only
    // needed for passwords, which people choose and attackers can guess
    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is missing from this JVM", e);
        }
    }
}
