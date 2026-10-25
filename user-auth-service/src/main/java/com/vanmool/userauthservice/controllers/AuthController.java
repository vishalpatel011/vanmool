package com.vanmool.userauthservice.controllers;

import com.vanmool.userauthservice.dtos.LoginRequestDto;
import com.vanmool.userauthservice.dtos.LoginResponseDto;
import com.vanmool.userauthservice.dtos.SignupRequestDto;
import com.vanmool.userauthservice.dtos.UserDto;
import com.vanmool.userauthservice.exceptions.InvalidTokenException;
import com.vanmool.userauthservice.models.User;
import com.vanmool.userauthservice.services.UserService;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// Signup, login and logout are POST requests: each one creates or changes something (an account, a token)
@RestController
@RequestMapping("/auth")
public class AuthController {
    private static final String BEARER_PREFIX = "Bearer ";

    private final UserService userService;

    public AuthController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping("/signup")
    public ResponseEntity<UserDto> signup(@Valid @RequestBody SignupRequestDto signupRequestDto) {
        User user = userService.signup(signupRequestDto.getName(),
                signupRequestDto.getEmail(),
                signupRequestDto.getPassword());
        return new ResponseEntity<>(UserDto.from(user), HttpStatus.CREATED);
    }

    @PostMapping("/login")
    public LoginResponseDto login(@Valid @RequestBody LoginRequestDto loginRequestDto) {
        return LoginResponseDto.from(userService.login(loginRequestDto.getEmail(), loginRequestDto.getPassword()));
    }

    // The token travels in the Authorization header, never in the URL, where it would end up in access logs
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        userService.logout(bearerToken(authorization));
        return new ResponseEntity<>(HttpStatus.NO_CONTENT);
    }

    // Answers who the token belongs to, or 401; other services can call this to check a token
    @GetMapping("/validate")
    public UserDto validate(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        return UserDto.from(userService.validateToken(bearerToken(authorization)));
    }

    private static String bearerToken(String authorization) {
        if(authorization == null || !authorization.startsWith(BEARER_PREFIX) || authorization.length() == BEARER_PREFIX.length()) {
            throw new InvalidTokenException();
        }
        return authorization.substring(BEARER_PREFIX.length()).trim();
    }
}
