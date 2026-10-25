package com.vanmool.cartservice.repositories;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.redis.DataRedisTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Integration test of the Redis commands behind a cart, against the Redis started by docker compose. @DataRedisTest
// starts only the Redis part of Spring (connection and templates), not the web layer or security.
// Each test uses fresh random user ids, so it never touches real carts, and removes its keys afterwards.
@DataRedisTest
@Import(CartRepository.class)
class CartRepositoryIT {
    private static final long THIRTY_DAYS_IN_SECONDS = 30L * 24 * 60 * 60;

    private final UUID userId = UUID.randomUUID();
    private final UUID otherUserId = UUID.randomUUID();
    private final UUID basketId = UUID.randomUUID();
    private final UUID biscuitsId = UUID.randomUUID();

    @Autowired
    private CartRepository cartRepository;

    @Autowired
    private StringRedisTemplate redis;

    @AfterEach
    void deleteKeys() {
        redis.delete(CartRepository.key(userId));
        redis.delete(CartRepository.key(otherUserId));
    }

    @Test
    void addQuantity_storesTheCartAsAHashUnderCartUserId() {
        // Act
        cartRepository.addQuantity(userId, basketId, 2);

        // Assert: what redis-cli HGETALL cart:{userId} shows
        assertEquals("hash", redis.type("cart:" + userId).code());
        assertEquals(Map.of(basketId.toString(), "2"), redis.opsForHash().entries("cart:" + userId));
    }

    @Test
    void addQuantity_addsToTheQuantityThere_andReturnsTheNewOne() {
        // Act
        int first = cartRepository.addQuantity(userId, basketId, 2);
        int second = cartRepository.addQuantity(userId, basketId, 3);
        int takenBack = cartRepository.addQuantity(userId, basketId, -3);

        // Assert
        assertEquals(2, first);
        assertEquals(5, second);
        assertEquals(2, takenBack);
        assertEquals(Map.of(basketId, 2), cartRepository.findItems(userId));
    }

    @Test
    void everyChange_setsTheExpiryToThirtyDaysAgain() {
        // Arrange: shorten the expiry, as if the cart had been idle for a while
        cartRepository.addQuantity(userId, basketId, 1);
        redis.expire(CartRepository.key(userId), 60, TimeUnit.SECONDS);

        // Act
        cartRepository.setQuantity(userId, biscuitsId, 4);

        // Assert
        long secondsLeft = redis.getExpire(CartRepository.key(userId), TimeUnit.SECONDS);
        assertTrue(secondsLeft > THIRTY_DAYS_IN_SECONDS - 60 && secondsLeft <= THIRTY_DAYS_IN_SECONDS,
                "expiry was " + secondsLeft + " s");
    }

    @Test
    void setQuantity_replacesTheQuantity() {
        // Arrange
        cartRepository.addQuantity(userId, basketId, 5);

        // Act
        cartRepository.setQuantity(userId, basketId, 1);

        // Assert
        assertEquals(Map.of(basketId, 1), cartRepository.findItems(userId));
    }

    @Test
    void containsProduct_andCountProducts_seeWhatIsInTheCart() {
        // Arrange
        cartRepository.addQuantity(userId, basketId, 1);
        cartRepository.addQuantity(userId, biscuitsId, 3);

        // Act & Assert: the count is of different products, not of pieces
        assertTrue(cartRepository.containsProduct(userId, basketId));
        assertFalse(cartRepository.containsProduct(userId, UUID.randomUUID()));
        assertEquals(2, cartRepository.countProducts(userId));
    }

    @Test
    void removingTheLastProduct_leavesNoKeyBehind() {
        // Arrange
        cartRepository.addQuantity(userId, basketId, 1);
        cartRepository.addQuantity(userId, biscuitsId, 1);

        // Act
        cartRepository.removeProduct(userId, basketId);
        boolean keyAfterFirst = redis.hasKey(CartRepository.key(userId));
        cartRepository.removeProduct(userId, biscuitsId);

        // Assert
        assertTrue(keyAfterFirst);
        assertFalse(redis.hasKey(CartRepository.key(userId)));
        assertEquals(Map.of(), cartRepository.findItems(userId));
    }

    @Test
    void deleteCart_removesOnlyThatUsersCart() {
        // Arrange
        cartRepository.addQuantity(userId, basketId, 1);
        cartRepository.addQuantity(otherUserId, basketId, 2);

        // Act
        cartRepository.deleteCart(userId);

        // Assert
        assertEquals(Map.of(), cartRepository.findItems(userId));
        assertEquals(Map.of(basketId, 2), cartRepository.findItems(otherUserId));
    }

    @Test
    void userWithoutACart_hasNoItems() {
        assertEquals(Map.of(), cartRepository.findItems(userId));
        assertEquals(0, cartRepository.countProducts(userId));
    }
}
