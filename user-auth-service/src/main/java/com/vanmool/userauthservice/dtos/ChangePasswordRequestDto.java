package com.vanmool.userauthservice.dtos;

import com.vanmool.userauthservice.validation.MaxBytes;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

// PUT /users/me/password. The current password is asked for again although the request carries a valid token: a token
// left on a shared or stolen device must not be enough to take over the account
@Getter
@Setter
public class ChangePasswordRequestDto {
    // No password set at signup can be longer than 72 bytes, and BCrypt cannot compare longer ones
    @NotBlank
    @MaxBytes(72)
    private String currentPassword;

    // The same rules as at signup
    @NotNull
    @Size(min = 8, message = "must be at least 8 characters long")
    @MaxBytes(72)
    private String newPassword;
}
