package com.vanmool.userauthservice.services;

import com.vanmool.userauthservice.exceptions.EmailAlreadyRegisteredException;
import com.vanmool.userauthservice.models.Role;
import com.vanmool.userauthservice.models.User;
import com.vanmool.userauthservice.repositories.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Locale;

@Service
public class UserServiceImpl implements UserService {
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public UserServiceImpl(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public User signup(String name, String email, String password) {
        String normalizedEmail = normalizeEmail(email);
        if(userRepository.existsByEmail(normalizedEmail)) {
            throw new EmailAlreadyRegisteredException(normalizedEmail);
        }

        User user = new User();
        user.setName(name.trim());
        user.setEmail(normalizedEmail);
        // Only the BCrypt hash is stored; it contains its own random salt
        user.setPasswordHash(passwordEncoder.encode(password));
        user.getRoles().add(Role.CUSTOMER);
        try {
            return userRepository.save(user);
        } catch (DataIntegrityViolationException e) {
            // Another request registered the same email after our check; the unique constraint stopped the duplicate
            throw new EmailAlreadyRegisteredException(normalizedEmail);
        }
    }

    private static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
