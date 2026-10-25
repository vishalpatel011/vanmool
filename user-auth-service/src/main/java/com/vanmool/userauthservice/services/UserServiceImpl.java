package com.vanmool.userauthservice.services;

import com.vanmool.userauthservice.exceptions.EmailAlreadyRegisteredException;
import com.vanmool.userauthservice.exceptions.InvalidCredentialsException;
import com.vanmool.userauthservice.exceptions.InvalidTokenException;
import com.vanmool.userauthservice.exceptions.UserNotFoundException;
import com.vanmool.userauthservice.models.Role;
import com.vanmool.userauthservice.models.Token;
import com.vanmool.userauthservice.models.User;
import com.vanmool.userauthservice.repositories.TokenRepository;
import com.vanmool.userauthservice.repositories.UserRepository;
import com.vanmool.userauthservice.security.models.CustomUserDetails;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.UUID;

@Service
public class UserServiceImpl implements UserService {
    private final UserRepository userRepository;
    private final TokenRepository tokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final Clock clock;
    private final Duration tokenValidity;

    public UserServiceImpl(UserRepository userRepository, TokenRepository tokenRepository, PasswordEncoder passwordEncoder,
                           AuthenticationManager authenticationManager, JwtService jwtService, Clock clock,
                           @Value("${auth.token-validity}") Duration tokenValidity) {
        this.userRepository = userRepository;
        this.tokenRepository = tokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
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
        // Spring Security checks the password: CustomUserDetailsService finds the user, the PasswordEncoder compares
        // BCrypt hashes. An unknown email and a wrong password end in the same exception, and for an unknown email the
        // provider still spends one BCrypt comparison, so the response time does not reveal which emails exist.
        CustomUserDetails user;
        try {
            user = (CustomUserDetails) authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(normalizeEmail(email), password)).getPrincipal();
        } catch (AuthenticationException e) {
            throw new InvalidCredentialsException();
        }

        // The token row is saved first: its generated id becomes the JWT's "jti", which logout uses to revoke it.
        // Whole seconds, because that is all a JWT's iat and exp can hold
        Instant issuedAt = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        Token token = new Token();
        // A reference is enough for the foreign key; the user was loaded during authentication
        token.setUser(userRepository.getReferenceById(user.getUserId()));
        token.setExpiresAt(issuedAt.plus(tokenValidity));
        Token savedToken = tokenRepository.save(token);

        String jwt = jwtService.issue(user.getUserId(), user.getUsername(), user.getRoles(),
                savedToken.getId(), issuedAt, savedToken.getExpiresAt());
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

    @Override
    public User getUser(UUID userId) {
        return userRepository.findWithRolesById(userId).orElseThrow(() -> new UserNotFoundException(userId));
    }

    // Granting a role that the user already has changes nothing (roles are a set), so repeating the request is safe
    @Override
    @Transactional
    public User grantRole(UUID userId, Role role) {
        User user = getUser(userId);
        user.getRoles().add(role);
        return userRepository.save(user);
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
