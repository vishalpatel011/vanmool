package com.vanmool.userauthservice.dtos;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

// Only "present" is checked here: the signup rules decide what a valid password is, login only compares
@Getter
@Setter
public class LoginRequestDto {
    @NotBlank
    private String email;

    @NotBlank
    private String password;
}
