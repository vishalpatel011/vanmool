package com.vanmool.userauthservice.services;

import com.vanmool.userauthservice.configs.KafkaConfig;
import com.vanmool.userauthservice.events.OutboxWriter;
import com.vanmool.userauthservice.events.PasswordChangedEvent;
import com.vanmool.userauthservice.events.PasswordResetRequestedEvent;
import com.vanmool.userauthservice.exceptions.InvalidResetLinkException;
import com.vanmool.userauthservice.exceptions.UserNotFoundException;
import com.vanmool.userauthservice.models.PasswordResetToken;
import com.vanmool.userauthservice.models.User;
import com.vanmool.userauthservice.repositories.PasswordResetTokenRepository;
import com.vanmool.userauthservice.repositories.TokenRepository;
import com.vanmool.userauthservice.repositories.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;

// Password reset through a link sent by email (PRD 1.4):
//   1. requestReset: a random token; its hash is stored, the link with the token goes out in PasswordResetRequested
//   2. resetPassword: the token is used once, the new password is set, every session ends, PasswordChanged goes out
// Asking for a link answers the same whether the email has an account or not, so the endpoint cannot be used to find
// out who has an account.
@Slf4j
@Service
public class PasswordResetService {
    // At most this many links per account per hour: a stranger cannot fill someone's mailbox
    static final int MAX_LINKS_PER_HOUR = 3;
    // 32 bytes = 256 bits from the operating system's secure random source; in Base64url, 43 characters
    private static final int TOKEN_BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final PasswordResetTokenRepository resetTokenRepository;
    private final TokenRepository tokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final OutboxWriter outboxWriter;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;
    private final Duration linkValidity;
    private final String linkPrefix;

    public PasswordResetService(UserRepository userRepository, PasswordResetTokenRepository resetTokenRepository,
                                TokenRepository tokenRepository, PasswordEncoder passwordEncoder,
                                OutboxWriter outboxWriter, TransactionTemplate transactionTemplate, Clock clock,
                                @Value("${auth.password-reset.validity}") Duration linkValidity,
                                @Value("${auth.password-reset.link}") String linkPrefix) {
        this.userRepository = userRepository;
        this.resetTokenRepository = resetTokenRepository;
        this.tokenRepository = tokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.outboxWriter = outboxWriter;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
        this.linkValidity = linkValidity;
        this.linkPrefix = linkPrefix;
    }

    public void requestReset(String email) {
        Optional<User> found = userRepository.findByEmail(email.trim().toLowerCase(Locale.ROOT));
        if (found.isEmpty()) {
            // Nothing to send; the caller gets the same answer as for an account
            log.info("Password reset asked for an email that has no account");
            return;
        }
        User user = found.get();
        Instant now = clock.instant();
        if (resetTokenRepository.countByUserIdAndCreatedAtAfter(user.getId(), now.minus(Duration.ofHours(1))) >= MAX_LINKS_PER_HOUR) {
            log.warn("Password reset for user {} refused: {} links in the last hour already", user.getId(), MAX_LINKS_PER_HOUR);
            return;
        }

        String token = newToken();
        PasswordResetToken link = new PasswordResetToken();
        link.setUser(user);
        link.setTokenHash(sha256(token));
        link.setExpiresAt(now.plus(linkValidity));
        // The link's row and the event that emails it, in one transaction: an email is sent exactly for a stored link
        transactionTemplate.executeWithoutResult(status -> {
            resetTokenRepository.save(link);
            outboxWriter.add(KafkaConfig.USER_EVENTS_TOPIC, user.getId().toString(), PasswordResetRequestedEvent.of(
                    user.getId(), user.getName(), user.getEmail(), linkPrefix + token, link.getExpiresAt(), now));
        });
    }

    public void resetPassword(String token, String newPassword) {
        Instant now = clock.instant();
        PasswordResetToken link = resetTokenRepository.findWithUserByTokenHash(sha256(token))
                .filter(found -> found.isUsable(now))
                .orElseThrow(InvalidResetLinkException::new);
        // BCrypt (about 0.3 s) before the transaction, as at signup
        String newHash = passwordEncoder.encode(newPassword);
        User user = link.getUser();
        transactionTemplate.executeWithoutResult(status -> {
            if (resetTokenRepository.use(link.getId(), now) == 0) {
                throw new InvalidResetLinkException();
            }
            User managed = userRepository.findById(user.getId()).orElseThrow(() -> new UserNotFoundException(user.getId()));
            managed.setPasswordHash(newHash);
            resetTokenRepository.closeOpenLinks(user.getId(), now);
            // Whoever had the old password may be signed in somewhere: every session ends
            tokenRepository.revokeOtherTokens(user.getId(), null, now);
            outboxWriter.add(KafkaConfig.USER_EVENTS_TOPIC, user.getId().toString(),
                    PasswordChangedEvent.of(user.getId(), managed.getName(), managed.getEmail(), now));
        });
    }

    private static String newToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String sha256(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Every Java runtime has SHA-256", e);
        }
    }
}
