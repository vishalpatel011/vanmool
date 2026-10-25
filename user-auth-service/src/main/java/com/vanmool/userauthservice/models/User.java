package com.vanmool.userauthservice.models;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.HashSet;
import java.util.Set;

@Getter
@Setter
@Entity
@Table(name = "users") // USER is an SQL keyword, so the table is plural
public class User extends Base {
    @Column(nullable = false, length = 100)
    private String name;
    // Stored trimmed and in lower case, so "Asha@Example.com" and "asha@example.com" are the same account
    @Column(nullable = false, unique = true)
    private String email;
    // BCrypt hash: "$2a$12$" + 22-character random salt + 31-character hash. The password itself is never stored
    @Column(nullable = false)
    private String passwordHash;
    // One row per role in user_role; stored as text (VARCHAR), not MySQL's ENUM type, so adding a role needs no ALTER
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "user_role", joinColumns = @JoinColumn(name = "user_id"))
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "role", nullable = false, length = 20)
    private Set<Role> roles = new HashSet<>();
}
