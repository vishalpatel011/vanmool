package com.vanmool.userauthservice.controllers;

import com.vanmool.userauthservice.dtos.SignupRequestDto;
import com.vanmool.userauthservice.dtos.UserDto;
import com.vanmool.userauthservice.models.User;
import com.vanmool.userauthservice.services.UserService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// Signup, login and logout are POST requests: each one creates or changes something (an account, a token)
@RestController
@RequestMapping("/auth")
public class AuthController {
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
}
