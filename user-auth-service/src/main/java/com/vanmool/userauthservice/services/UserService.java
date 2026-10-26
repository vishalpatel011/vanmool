package com.vanmool.userauthservice.services;

import com.vanmool.userauthservice.models.Role;
import com.vanmool.userauthservice.models.User;

import java.util.UUID;

public interface UserService {
    User signup(String name, String email, String password);

    IssuedToken login(String email, String password);

    void logout(String token);

    User validateToken(String token);

    User getUser(UUID userId);

    User grantRole(UUID userId, Role role);

    User updateProfile(UUID userId, String name);

    void changePassword(UUID userId, UUID currentTokenId, String currentPassword, String newPassword);
}
