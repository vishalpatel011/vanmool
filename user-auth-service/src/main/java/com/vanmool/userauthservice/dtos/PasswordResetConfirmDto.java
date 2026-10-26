package com.vanmool.userauthservice.dtos;

import com.vanmool.userauthservice.validation.MaxBytes;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

// POST /auth/password-reset/confirm: the token from the link, and the new password (the signup rules)
@Getter
@Setter
public class PasswordResetConfirmDto {
    @NotBlank
    @Size(max = 100)
    private String token;

    @NotNull
    @Size(min = 8, message = "must be at least 8 characters long")
    @MaxBytes(72)
    private String newPassword;
}
