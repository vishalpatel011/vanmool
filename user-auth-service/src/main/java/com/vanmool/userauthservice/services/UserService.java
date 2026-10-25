package com.vanmool.userauthservice.services;

import com.vanmool.userauthservice.models.User;

public interface UserService {
    User signup(String name, String email, String password);

    IssuedToken login(String email, String password);

    void logout(String token);

    User validateToken(String token);
}
