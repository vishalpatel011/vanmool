package com.vanmool.userauthservice.services;

import com.vanmool.userauthservice.configs.KafkaConfig;
import com.vanmool.userauthservice.events.OutboxWriter;
import com.vanmool.userauthservice.events.TokenRevokedEvent;
import com.vanmool.userauthservice.models.Token;
import com.vanmool.userauthservice.repositories.TokenRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

// Ends sessions (token rows) and says so: every token it ends gets a TokenRevoked event in the outbox, in the same
// transaction, so the API gateway refuses that token at once instead of until it expires. It runs inside the caller's
// transaction (MANDATORY), so the token rows, the password change and the events are saved together or not at all.
@Component
public class SessionRevoker {
    private final TokenRepository tokenRepository;
    private final OutboxWriter outboxWriter;
    private final Clock clock;

    public SessionRevoker(TokenRepository tokenRepository, OutboxWriter outboxWriter, Clock clock) {
        this.tokenRepository = tokenRepository;
        this.outboxWriter = outboxWriter;
        this.clock = clock;
    }

    // Logout: one token. One that is already revoked changes nothing and is not announced again
    @Transactional(propagation = Propagation.MANDATORY)
    public void revoke(Token token) {
        if (token.isRevoked()) {
            return;
        }
        token.setRevoked(true);
        tokenRepository.save(token);
        announce(token);
    }

    // A password change or reset: every session of the user that could still be used (not revoked, not expired), except
    // keep, the token the change was made with (null: none is kept). The rows are managed entities of the transaction,
    // so the changes are written when it commits. Returns how many sessions were ended
    @Transactional(propagation = Propagation.MANDATORY)
    public int revokeOtherSessions(UUID userId, UUID keep) {
        List<Token> usable = tokenRepository.findByUserIdAndRevokedFalseAndExpiresAtAfter(userId, clock.instant());
        int revoked = 0;
        for (Token token : usable) {
            if (token.getId().equals(keep)) {
                continue;
            }
            token.setRevoked(true);
            announce(token);
            revoked++;
        }
        return revoked;
    }

    // Keyed by the user id, like the other user events, so one user's events stay in order
    private void announce(Token token) {
        UUID userId = token.getUser().getId();
        Instant now = clock.instant();
        outboxWriter.add(KafkaConfig.USER_EVENTS_TOPIC, userId.toString(),
                TokenRevokedEvent.of(userId, token.getId(), token.getExpiresAt(), now));
    }
}
