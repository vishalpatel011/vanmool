package com.vanmool.productservice.controllers;

import com.vanmool.productservice.assistant.AssistantUnavailableException;
import com.vanmool.productservice.assistant.ShoppingAssistant;
import com.vanmool.productservice.dtos.AssistantAnswerDto;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// The web layer of the shopping assistant, with the assistant replaced by a mock (access: ProductSecurityWebMvcTest)
@WebMvcTest(ShoppingAssistantController.class)
@AutoConfigureMockMvc(addFilters = false)
class ShoppingAssistantControllerWebMvcTest {
    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ShoppingAssistant shoppingAssistant;

    @Test
    void aQuestion_getsTheAnswer_andTheProductsItIsBasedOn() throws Exception {
        UUID basketId = UUID.fromString("150dac81-6130-4903-9385-9b682db879b3");
        when(shoppingAssistant.answer("Which products are from Dangs?")).thenReturn(new AssistantAnswerDto(
                "The Handcrafted Bamboo Basket (₹499.99) comes from Dangs.",
                List.of(new AssistantAnswerDto.ProductRef(basketId, "Handcrafted Bamboo Basket", "499.99", "Traditional Crafts", "Dangs, Gujarat"))));

        mockMvc.perform(post("/assistant/questions").contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"question\": \"Which products are from Dangs?\" }"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("The Handcrafted Bamboo Basket (₹499.99) comes from Dangs."))
                .andExpect(jsonPath("$.products[0].id").value(basketId.toString()))
                .andExpect(jsonPath("$.products[0].originRegion").value("Dangs, Gujarat"));
    }

    @Test
    void aBlankOrTooLongQuestion_is400_withoutAskingTheModel() throws Exception {
        mockMvc.perform(post("/assistant/questions").contentType(MediaType.APPLICATION_JSON).content("{ \"question\": \"\" }"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));
        mockMvc.perform(post("/assistant/questions").contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"question\": \"" + "a".repeat(301) + "\" }"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(shoppingAssistant);
    }

    @Test
    void anUnavailableAssistant_is503() throws Exception {
        when(shoppingAssistant.answer(anyString())).thenThrow(new AssistantUnavailableException(
                "The shopping assistant is not available right now; please try again shortly", null));

        mockMvc.perform(post("/assistant/questions").contentType(MediaType.APPLICATION_JSON).content("{ \"question\": \"bamboo\" }"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("ASSISTANT_UNAVAILABLE"));
    }
}
