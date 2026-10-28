package com.vanmool.productservice.controllers;

import com.vanmool.productservice.assistant.AssistantUnavailableException;
import com.vanmool.productservice.assistant.ListingAssistant;
import com.vanmool.productservice.dtos.ListingDraftDto;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// The web layer of the listing assistant, with the assistant replaced by a mock (security: ProductSecurityWebMvcTest)
@WebMvcTest(ListingAssistantController.class)
@AutoConfigureMockMvc(addFilters = false)
class ListingAssistantControllerWebMvcTest {
    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ListingAssistant listingAssistant;

    @Test
    void notes_giveADraft_withTheProductsFields() throws Exception {
        // Arrange
        when(listingAssistant.draft("bamboo basket, hand-woven by our women's group in Ahwa"))
                .thenReturn(new ListingDraftDto("Hand-Woven Bamboo Basket", "A basket woven by hand from bamboo.",
                        "Woven by a women's group in Ahwa.", "Bamboo", "Ahwa, Gujarat", "Traditional Crafts"));

        // Act & Assert
        mockMvc.perform(post("/products/listing-drafts").contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"notes\": \"bamboo basket, hand-woven by our women's group in Ahwa\" }"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Hand-Woven Bamboo Basket"))
                .andExpect(jsonPath("$.originRegion").value("Ahwa, Gujarat"))
                .andExpect(jsonPath("$.category").value("Traditional Crafts"));
    }

    @Test
    void blankOrTooLongNotes_are400_withoutAskingTheModel() throws Exception {
        mockMvc.perform(post("/products/listing-drafts").contentType(MediaType.APPLICATION_JSON).content("{ \"notes\": \" \" }"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));
        mockMvc.perform(post("/products/listing-drafts").contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"notes\": \"" + "a".repeat(1001) + "\" }"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(listingAssistant);
    }

    @Test
    void anUnavailableModel_is503() throws Exception {
        when(listingAssistant.draft(anyString())).thenThrow(new AssistantUnavailableException(
                "The listing assistant is not available right now; please try again shortly", null));

        mockMvc.perform(post("/products/listing-drafts").contentType(MediaType.APPLICATION_JSON).content("{ \"notes\": \"ragi biscuits\" }"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("ASSISTANT_UNAVAILABLE"));
    }
}
