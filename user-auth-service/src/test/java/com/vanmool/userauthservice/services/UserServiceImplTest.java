package com.vanmool.userauthservice.services;

import com.vanmool.userauthservice.exceptions.EmailAlreadyRegisteredException;
import com.vanmool.userauthservice.models.Role;
import com.vanmool.userauthservice.models.User;
import com.vanmool.userauthservice.repositories.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// Plain unit test: the repository and the password encoder are Mockito mocks, so no database and no slow BCrypt
@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {
    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    private UserServiceImpl userService;

    @BeforeEach
    void setUp() {
        userService = new UserServiceImpl(userRepository, passwordEncoder);
    }

    @Nested
    class Signup {
        @Test
        void storesTheNormalizedEmailTheHashNotThePasswordAndTheCustomerRole() {
            // Arrange
            when(userRepository.existsByEmail("asha@example.com")).thenReturn(false);
            when(passwordEncoder.encode("correct horse battery")).thenReturn("$2a$12$hashed");
            when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

            // Act
            User result = userService.signup("  Asha Gamit ", "  Asha@Example.COM ", "correct horse battery");

            // Assert: the service builds the User itself, so a captor catches what was saved
            ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
            verify(userRepository).save(saved.capture());
            assertSame(saved.getValue(), result);
            assertAll(
                    () -> assertEquals("Asha Gamit", result.getName()),
                    () -> assertEquals("asha@example.com", result.getEmail()),
                    () -> assertEquals("$2a$12$hashed", result.getPasswordHash()),
                    () -> assertEquals(Set.of(Role.CUSTOMER), result.getRoles())
            );
        }

        @Test
        void whenEmailIsTaken_inAnyLetterCase_throwsAndSavesNothing() {
            // Arrange
            when(userRepository.existsByEmail("asha@example.com")).thenReturn(true);

            // Act & Assert
            assertThrows(EmailAlreadyRegisteredException.class,
                    () -> userService.signup("Asha", "ASHA@example.com", "correct horse battery"));
            verify(userRepository, never()).save(any());
            verify(passwordEncoder, never()).encode(any());
        }

        @Test
        void whenAnotherSignupWithTheSameEmailWinsTheRace_throwsEmailAlreadyRegistered() {
            // Arrange: the check passed, but a concurrent request inserted the email first and the unique key stopped ours
            when(userRepository.existsByEmail("asha@example.com")).thenReturn(false);
            when(passwordEncoder.encode(any())).thenReturn("$2a$12$hashed");
            when(userRepository.save(any(User.class)))
                    .thenThrow(new DataIntegrityViolationException("Duplicate entry 'asha@example.com' for key 'uk_users_email'"));

            // Act & Assert
            assertThrows(EmailAlreadyRegisteredException.class,
                    () -> userService.signup("Asha", "asha@example.com", "correct horse battery"));
        }
    }
}
