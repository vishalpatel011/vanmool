package com.vanmool.paymentservice;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// The OpenAPI description and Swagger UI, in the running application: they need no token, and the description is the
// one the API gateway's Swagger page shows for this service.
@SpringBootTest
@AutoConfigureMockMvc
class ApiDocsIT {
    @Autowired
    private MockMvc mockMvc;

    @Test
    void theApiDescription_isPublic_withTheServiceTitle_aBearerToken_andRelativeServer_withoutTheFakeGateway() throws Exception {
        // Act & Assert: the server "/" makes "Try it out" use the address the description came from (the gateway, and leaves out the fake gateway)
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("Vanmool payment-service API"))
                .andExpect(jsonPath("$.servers[0].url").value("/"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
                .andExpect(jsonPath("$.paths['/payments'].post.summary").value(startsWith("Start a payment")))
                // The fake gateway's pages are a development tool, not part of the API
                .andExpect(jsonPath("$.paths['/fake-gateway/checkout/{reference}']").doesNotExist());
    }

    @Test
    void swaggerUi_isServedWithoutAToken() throws Exception {
        mockMvc.perform(get("/swagger-ui.html")).andExpect(status().is3xxRedirection());
        mockMvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
    }
}
