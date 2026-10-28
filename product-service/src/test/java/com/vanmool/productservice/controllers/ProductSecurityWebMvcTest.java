package com.vanmool.productservice.controllers;

import com.vanmool.productservice.assistant.ListingAssistant;
import com.vanmool.productservice.assistant.ShoppingAssistant;
import com.vanmool.productservice.configs.SecurityConfig;
import com.vanmool.productservice.dtos.AssistantAnswerDto;
import com.vanmool.productservice.dtos.ListingDraftDto;
import com.vanmool.productservice.dtos.ProductResponseDto;
import com.vanmool.productservice.dtos.StockReservationResponseDto;
import com.vanmool.productservice.models.Product;
import com.vanmool.productservice.models.ReservationStatus;
import com.vanmool.productservice.security.RestSecurityErrorHandler;
import com.vanmool.productservice.services.CategoryService;
import com.vanmool.productservice.services.ProductService;
import com.vanmool.productservice.services.StockService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Who may call what, with the real security rules. jwt() stands in for a checked token with chosen roles; the
// services are mocks, so a request that passes the rules gets an ordinary answer.
@WebMvcTest({ProductController.class, StockController.class, CategoryController.class, ListingAssistantController.class,
        ShoppingAssistantController.class})
@Import({SecurityConfig.class, RestSecurityErrorHandler.class})
class ProductSecurityWebMvcTest {
    private static final UUID PRODUCT_ID = UUID.fromString("150dac81-6130-4903-9385-9b682db879b3");
    private static final String PRODUCT = """
            { "name": "Handcrafted Bamboo Basket", "price": 499.99 }""";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtAuthenticationConverter jwtAuthenticationConverter;

    @MockitoBean
    private ProductService productService;

    @MockitoBean
    private StockService stockService;

    @MockitoBean
    private CategoryService categoryService;

    @MockitoBean
    private ListingAssistant listingAssistant;

    @MockitoBean
    private ShoppingAssistant shoppingAssistant;

    // The real one is built from user-auth-service's keys (JwtDecoderConfig); jwt() below skips decoding
    @MockitoBean
    private JwtDecoder jwtDecoder;

    private static RequestPostProcessor tokenWith(String... roles) {
        List<GrantedAuthority> authorities = java.util.Arrays.stream(roles)
                .<GrantedAuthority>map(role -> new SimpleGrantedAuthority("ROLE_" + role)).toList();
        return jwt().jwt(token -> token.subject(UUID.randomUUID().toString())).authorities(authorities);
    }

    private static Product basket() {
        Product product = new Product();
        product.setId(PRODUCT_ID);
        product.setName("Handcrafted Bamboo Basket");
        product.setPrice(new BigDecimal("499.99"));
        return product;
    }

    @Test
    void theCatalog_isReadWithoutAToken() throws Exception {
        when(productService.searchProducts(any(), any())).thenReturn(Page.empty());
        when(productService.getProductById(PRODUCT_ID)).thenReturn(ProductResponseDto.from(basket()));

        mockMvc.perform(get("/products")).andExpect(status().isOk());
        mockMvc.perform(get("/products/{id}", PRODUCT_ID)).andExpect(status().isOk());
        mockMvc.perform(get("/categories")).andExpect(status().isOk());
        mockMvc.perform(get("/products/{id}/stock", PRODUCT_ID)).andExpect(status().isOk());
    }

    @Test
    void changingAProduct_withoutAToken_is401_inTheServicesErrorFormat() throws Exception {
        mockMvc.perform(post("/products").contentType(MediaType.APPLICATION_JSON).content(PRODUCT))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(jsonPath("$.error").value("INVALID_TOKEN"))
                .andExpect(jsonPath("$.path").value("/products"));
        verifyNoInteractions(productService);
    }

    @Test
    void aCustomer_cannotCreateChangeOrDeleteProducts_norAdjustStock() throws Exception {
        RequestPostProcessor customer = tokenWith("CUSTOMER");

        mockMvc.perform(post("/products").with(customer).contentType(MediaType.APPLICATION_JSON).content(PRODUCT))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));
        mockMvc.perform(put("/products/{id}", PRODUCT_ID).with(customer).contentType(MediaType.APPLICATION_JSON).content(PRODUCT))
                .andExpect(status().isForbidden());
        mockMvc.perform(patch("/products/{id}", PRODUCT_ID).with(customer)
                        .contentType("application/json-patch+json").content("[]"))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/products/{id}", PRODUCT_ID).with(customer)).andExpect(status().isForbidden());
        mockMvc.perform(post("/products/{id}/stock-adjustments", PRODUCT_ID).with(customer)
                        .contentType(MediaType.APPLICATION_JSON).content("{ \"change\": 5 }"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(productService, stockService);
    }

    @Test
    void theShoppingAssistant_answersAnyone_withoutAToken() throws Exception {
        when(shoppingAssistant.answer("Which products are from Dangs?")).thenReturn(new AssistantAnswerDto("The basket.", List.of()));

        mockMvc.perform(post("/assistant/questions").contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"question\": \"Which products are from Dangs?\" }"))
                .andExpect(status().isOk());
    }

    @Test
    void theListingAssistant_isForSellersAndAdmins_only() throws Exception {
        when(listingAssistant.draft(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new ListingDraftDto("Ragi Biscuits", "Biscuits.", null, "Ragi", null, "Natural Foods"));
        String notes = "{ \"notes\": \"ragi biscuits\" }";

        mockMvc.perform(post("/products/listing-drafts").contentType(MediaType.APPLICATION_JSON).content(notes))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/products/listing-drafts").with(tokenWith("CUSTOMER")).contentType(MediaType.APPLICATION_JSON).content(notes))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/products/listing-drafts").with(tokenWith("SELLER")).contentType(MediaType.APPLICATION_JSON).content(notes))
                .andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {"SELLER", "ADMIN"})
    void aSellerOrAnAdmin_canCreateAndDeleteProducts(String role) throws Exception {
        when(productService.createProduct(any())).thenReturn(basket());

        mockMvc.perform(post("/products").with(tokenWith("CUSTOMER", role)).contentType(MediaType.APPLICATION_JSON).content(PRODUCT))
                .andExpect(status().isCreated());
        mockMvc.perform(delete("/products/{id}", PRODUCT_ID).with(tokenWith(role))).andExpect(status().isNoContent());
    }

    @Test
    void stockReservations_needAToken_ofAnySignedInUser() throws Exception {
        UUID orderId = UUID.randomUUID();
        String request = "{ \"orderId\": \"" + orderId + "\", \"items\": [ { \"productId\": \"" + PRODUCT_ID + "\", \"quantity\": 1 } ] }";
        when(stockService.reserve(any(), any())).thenReturn(
                new StockService.ReservationResult(new StockReservationResponseDto(orderId, ReservationStatus.RESERVED, List.of()), true));

        mockMvc.perform(post("/stock-reservations").contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/stock-reservations/{orderId}", orderId)).andExpect(status().isUnauthorized());

        mockMvc.perform(post("/stock-reservations").with(tokenWith("CUSTOMER")).contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isCreated());
        mockMvc.perform(delete("/stock-reservations/{orderId}", orderId).with(tokenWith("CUSTOMER"))).andExpect(status().isNoContent());
    }

    // The real converter: user-auth-service's "roles" claim becomes the roles hasAnyRole checks
    @Test
    void rolesClaim_becomesSpringRoles() {
        Jwt token = Jwt.withTokenValue("eyJ.seller.token").header("alg", "RS256").subject(UUID.randomUUID().toString())
                .claim("roles", List.of("CUSTOMER", "SELLER")).build();

        assertEquals(List.of("ROLE_CUSTOMER", "ROLE_SELLER"), jwtAuthenticationConverter.convert(token).getAuthorities()
                .stream().map(GrantedAuthority::getAuthority).sorted().toList());
    }
}
