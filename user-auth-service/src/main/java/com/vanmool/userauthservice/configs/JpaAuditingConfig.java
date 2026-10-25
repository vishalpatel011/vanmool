package com.vanmool.userauthservice.configs;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

// Turns on the AuditingEntityListener used by Base (@CreatedDate, @LastModifiedDate).
// It lives in its own class, not on the application class, so web layer tests (@WebMvcTest) can start without JPA.
@Configuration
@EnableJpaAuditing
public class JpaAuditingConfig {
}
