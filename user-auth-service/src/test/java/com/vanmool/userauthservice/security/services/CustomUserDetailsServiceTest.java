package com.vanmool.userauthservice.security.services;

import com.vanmool.userauthservice.models.Role;
import com.vanmool.userauthservice.models.User;
import com.vanmool.userauthservice.repositories.UserRepository;
import com.vanmool.userauthservice.security.models.CustomUserDetails;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CustomUserDetailsServiceTest {
    private static final UUID USER_ID = UUID.fromString("5b0e8f3c-2a41-4c47-9d0a-6f1c2e7b9a10");

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private CustomUserDetailsService customUserDetailsService;

    private static User asha() {
        User user = new User();
        user.setId(USER_ID);
        user.setEmail("asha@example.com");
        user.setPasswordHash("$2a$12$ashasHash");
        user.getRoles().add(Role.SELLER);
        user.getRoles().add(Role.CUSTOMER);
        return user;
    }

    @Test
    void knownEmail_givesSpringSecurityTheHashTheRolesAndAUsableAccount() {
        // Arrange
        when(userRepository.findByEmail("asha@example.com")).thenReturn(Optional.of(asha()));

        // Act: the email is matched in any letter case, like at signup
        UserDetails userDetails = customUserDetailsService.loadUserByUsername("  Asha@Example.com ");

        // Assert
        CustomUserDetails details = assertInstanceOf(CustomUserDetails.class, userDetails);
        assertAll(
                () -> assertEquals("asha@example.com", details.getUsername()),
                () -> assertEquals("$2a$12$ashasHash", details.getPassword()),
                () -> assertEquals(USER_ID, details.getUserId()),
                () -> assertEquals(Set.of(Role.CUSTOMER, Role.SELLER), details.getRoles()),
                // Spring's convention: hasRole("SELLER") looks for the authority ROLE_SELLER
                () -> assertEquals(List.of("ROLE_CUSTOMER", "ROLE_SELLER"),
                        details.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList()),
                () -> assertTrue(details.isEnabled() && details.isAccountNonLocked()
                        && details.isAccountNonExpired() && details.isCredentialsNonExpired())
        );
    }

    @Test
    void unknownEmail_throwsUsernameNotFound() {
        // Arrange
        when(userRepository.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

        // Act & Assert: the authentication provider turns this into the same "bad credentials" as a wrong password
        assertThrows(UsernameNotFoundException.class, () -> customUserDetailsService.loadUserByUsername("nobody@example.com"));
    }
}
