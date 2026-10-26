package com.vanmool.userauthservice.dtos;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

// POST /auth/password-reset
@Getter
@Setter
public class PasswordResetRequestDto {
    @NotBlank
    @Email
    @Size(max = 255)
    private String email;
}
