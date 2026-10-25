package com.vanmool.userauthservice;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

// Integration test: starts the whole application against the MySQL database in DB_URL. Starting at all proves that
// Flyway's migrations apply and that Hibernate's ddl-auto=validate accepts the schema they build for the entities.
@SpringBootTest
class UserAuthServiceApplicationIT {

    @Test
    void contextLoads() {
    }

}
