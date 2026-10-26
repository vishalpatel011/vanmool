-- Password reset links (PRD 1.4). The link carries a random token; only its SHA-256 hash is stored, so a copy of this
-- table gives nobody a usable link. A link works once, until expires_at.

CREATE TABLE password_reset_token
(
    id            BINARY(16)  NOT NULL,
    user_id       BINARY(16)  NOT NULL,
    token_hash    VARCHAR(64) NOT NULL,
    expires_at    DATETIME(6) NOT NULL,
    -- Set when the link is used, and on the user's other open links at the same moment: then none of them works again
    used_at       DATETIME(6),
    created_at    DATETIME(6) NOT NULL,
    last_modified DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_password_reset_token_hash UNIQUE (token_hash),
    CONSTRAINT fk_password_reset_token_user FOREIGN KEY (user_id) REFERENCES users (id),
    -- How many links a user asked for in the last hour (at most 3 are sent)
    INDEX idx_password_reset_token_user_created_at (user_id, created_at)
) ENGINE = InnoDB;
