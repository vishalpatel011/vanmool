package com.vanmool.userauthservice.security.models;

import com.vanmool.userauthservice.models.Role;
import com.vanmool.userauthservice.models.User;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

// A Vanmool user as Spring Security sees it. UserDetails is an interface, so Spring Security cannot create one
// itself: this class adapts our User entity. It copies the values it needs instead of keeping the entity, because it
// lives on in the login session after the database session is gone.
public class CustomUserDetails implements UserDetails {
    private final UUID userId;
    private final String username;
    private final String password;
    private final Set<Role> roles;
    private final List<CustomGrantedAuthority> authorities;

    public CustomUserDetails(User user) {
        this.userId = user.getId();
        // Users log in with their email, so it is the "username"
        this.username = user.getEmail();
        // The BCrypt hash: the authentication provider compares the given password against it
        this.password = user.getPasswordHash();
        this.roles = Set.copyOf(user.getRoles());
        this.authorities = user.getRoles().stream().sorted().map(CustomGrantedAuthority::new).toList();
    }

    public UUID getUserId() {
        return userId;
    }

    public Set<Role> getRoles() {
        return roles;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public String getPassword() {
        return password;
    }

    @Override
    public String getUsername() {
        return username;
    }

    // Vanmool has no account expiry, locking or password expiry yet, so every account is usable. Spring Security
    // checks these four flags at every login; a "disabled" or "locked" account would be refused here.
    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }
}
