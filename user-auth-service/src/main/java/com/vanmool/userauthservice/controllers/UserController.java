package com.vanmool.userauthservice.controllers;

import com.vanmool.userauthservice.dtos.UserDto;
import com.vanmool.userauthservice.models.Role;
import com.vanmool.userauthservice.services.UserService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

// Endpoints for a signed-in user. Spring Security has already checked the bearer JWT before these methods run
// (SecurityConfig), so authentication is not repeated here.
@RestController
@RequestMapping("/users")
public class UserController {
    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    // The JWT's "sub" claim is the user's id, in tokens from POST /auth/login and from /oauth2/token alike
    @GetMapping("/me")
    public UserDto me(@AuthenticationPrincipal Jwt jwt) {
        return UserDto.from(userService.getUser(UUID.fromString(jwt.getSubject())));
    }

    // ADMIN only (SecurityConfig). For example, a producer is given the SELLER role so that they can list products.
    // PUT because the result is the same however often the request is repeated.
    @PutMapping("/{id}/roles/{role}")
    public UserDto grantRole(@PathVariable("id") UUID id, @PathVariable("role") Role role) {
        return UserDto.from(userService.grantRole(id, role));
    }
}
