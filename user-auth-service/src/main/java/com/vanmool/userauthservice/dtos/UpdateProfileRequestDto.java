package com.vanmool.userauthservice.dtos;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

// PATCH /users/me: only the fields sent are changed. The email is not one of them: a new address has to be confirmed
// through a link sent to it first, and other services hold the email in tokens and orders
@Getter
@Setter
public class UpdateProfileRequestDto {
    // Optional, but when sent it needs at least one visible character
    @Size(max = 100)
    @Pattern(regexp = "(?s).*\\S.*", message = "must not be blank")
    private String name;
}
