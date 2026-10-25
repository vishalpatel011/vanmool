-- Accounts for user-auth-service. Ids are random UUIDs stored as BINARY(16).
-- Passwords are stored only as BCrypt hashes; emails are stored in lower case and are unique.

CREATE TABLE users
(
    id            BINARY(16)   NOT NULL,
    name          VARCHAR(100) NOT NULL,
    email         VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    created_at    DATETIME(6)  NOT NULL,
    last_modified DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_users_email UNIQUE (email)
) ENGINE = InnoDB;

-- One row per role a user holds (CUSTOMER, SELLER, ADMIN)
CREATE TABLE user_role
(
    user_id BINARY(16)  NOT NULL,
    role    VARCHAR(20) NOT NULL,
    PRIMARY KEY (user_id, role),
    CONSTRAINT fk_user_role_user FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE = InnoDB;
