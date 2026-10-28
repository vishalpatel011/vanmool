package com.vanmool.userauthservice.configs;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

// The OpenAPI description at /v3/api-docs, shown by Swagger UI here at /swagger-ui.html and, with every other service,
// on the API gateway's page. The server is "/", the address the description was fetched from: on the gateway's page
// "Try it out" goes through the gateway, here straight to this service. Authorize takes a token from POST /auth/login.
@Configuration
public class OpenApiConfig {
    @Bean
    public OpenAPI openApi() {
        return new OpenAPI()
                .info(new Info().title("Vanmool user-auth-service API").version("1.0")
                        .description("Accounts, sign-in and tokens, profiles, password reset and roles. The OAuth2 / OpenID Connect endpoints are listed at /.well-known/openid-configuration."))
                .servers(List.of(new Server().url("/")))
                .components(new Components().addSecuritySchemes("bearerAuth",
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"));
    }
}
