-- Tokens handed out at login. Only the SHA-256 hash of each token is stored, never the token itself.
-- Logout sets revoked; a token is accepted only while it is not revoked and expires_at is in the future.

CREATE TABLE token
(
    id            BINARY(16)  NOT NULL,
    user_id       BINARY(16)  NOT NULL,
    token_hash    VARCHAR(64) NOT NULL,
    expires_at    DATETIME(6) NOT NULL,
    revoked       BIT(1)      NOT NULL,
    created_at    DATETIME(6) NOT NULL,
    last_modified DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_token_token_hash UNIQUE (token_hash),
    CONSTRAINT fk_token_user FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE = InnoDB;
