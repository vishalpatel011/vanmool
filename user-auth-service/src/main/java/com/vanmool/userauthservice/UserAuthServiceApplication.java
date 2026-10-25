package com.vanmool.userauthservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;

// Spring Boot would otherwise create an in-memory user with a generated password and print it at startup.
// This service keeps its own users in user_db and checks them itself, so that default is switched off.
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class UserAuthServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(UserAuthServiceApplication.class, args);
    }

}
