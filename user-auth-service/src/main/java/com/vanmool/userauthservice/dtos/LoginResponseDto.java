package com.vanmool.userauthservice.dtos;

import com.vanmool.userauthservice.services.IssuedToken;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

// The client sends the token back as "Authorization: Bearer <token>"
@Getter
@Setter
public class LoginResponseDto {
    private String token;
    private String tokenType;
    private Instant expiresAt;

    public static LoginResponseDto from(IssuedToken issuedToken){
        LoginResponseDto loginResponseDto = new LoginResponseDto();
        loginResponseDto.token = issuedToken.value();
        loginResponseDto.tokenType = "Bearer";
        loginResponseDto.expiresAt = issuedToken.expiresAt();
        return loginResponseDto;
    }
}
