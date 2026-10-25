package com.vanmool.userauthservice.security.models;

import com.vanmool.userauthservice.models.Role;
import org.springframework.security.core.GrantedAuthority;

// A Vanmool role as Spring Security sees it. The "ROLE_" prefix is Spring's convention: hasRole("ADMIN") checks
// for the authority "ROLE_ADMIN"
public class CustomGrantedAuthority implements GrantedAuthority {
    private final String authority;

    public CustomGrantedAuthority(Role role) {
        this.authority = "ROLE_" + role.name();
    }

    @Override
    public String getAuthority() {
        return authority;
    }
}
