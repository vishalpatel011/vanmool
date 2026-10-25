package com.vanmool.userauthservice.security.services;

import com.vanmool.userauthservice.repositories.UserRepository;
import com.vanmool.userauthservice.security.models.CustomUserDetails;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.Locale;

// How Spring Security finds a user by the name typed at login: here, by email in user_db. Both login paths use it,
// POST /auth/login and the OAuth2 sign-in form, through the same authentication provider.
@Service
public class CustomUserDetailsService implements UserDetailsService {
    private final UserRepository userRepository;

    public CustomUserDetailsService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        // Emails are stored in lower case; findByEmail also fetches the roles, which become the authorities
        return userRepository.findByEmail(username.trim().toLowerCase(Locale.ROOT))
                .map(CustomUserDetails::new)
                .orElseThrow(() -> new UsernameNotFoundException("No user with this email"));
    }
}
