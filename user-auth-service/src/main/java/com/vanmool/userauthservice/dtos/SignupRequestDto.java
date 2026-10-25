package com.vanmool.userauthservice.dtos;

import com.vanmool.userauthservice.validation.MaxBytes;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SignupRequestDto {
    @NotBlank
    @Size(max = 100)
    private String name;

    @NotBlank
    @Email
    @Size(max = 255)
    private String email;

    // At least 8 characters and no composition rules, as NIST SP 800-63B recommends; at most 72 bytes for BCrypt
    @NotNull
    @Size(min = 8, message = "must be at least 8 characters long")
    @MaxBytes(72)
    private String password;
}
