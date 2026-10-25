package com.vanmool.cartservice.repositories;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

// A cart is one Redis hash per user:
//   key   cart:{userId}
//   field productId -> value quantity        (both stored as text)
// A hash fits a cart: one product appears once, a quantity can be changed in place with HINCRBY, which is atomic,
// so two quick "add" clicks never lose one, and the whole cart is read with one HGETALL. Only ids and quantities are
// kept; names and prices are always read from product-service, so a price change can never leave a stale price here.
// Every change also renews the key's expiry: a cart nobody touches for cart.time-to-live is removed by Redis itself.
@Repository
public class CartRepository {
    private static final String KEY_PREFIX = "cart:";

    private final StringRedisTemplate redisTemplate;
    private final HashOperations<String, String, String> hashOperations;
    private final Duration timeToLive;

    public CartRepository(StringRedisTemplate redisTemplate, @Value("${cart.time-to-live}") Duration timeToLive) {
        this.redisTemplate = redisTemplate;
        this.hashOperations = redisTemplate.opsForHash();
        this.timeToLive = timeToLive;
    }

    static String key(UUID userId) {
        return KEY_PREFIX + userId;
    }

    // HGETALL: every product in the cart with its quantity; empty when the user has no cart
    public Map<UUID, Integer> findItems(UUID userId) {
        Map<UUID, Integer> items = new LinkedHashMap<>();
        hashOperations.entries(key(userId))
                .forEach((productId, quantity) -> items.put(UUID.fromString(productId), Integer.parseInt(quantity)));
        return items;
    }

    // HEXISTS
    public boolean containsProduct(UUID userId, UUID productId) {
        return hashOperations.hasKey(key(userId), productId.toString());
    }

    // HLEN: how many different products the cart holds
    public long countProducts(UUID userId) {
        return hashOperations.size(key(userId));
    }

    // HINCRBY (creates the field if it is missing), then EXPIRE. Returns the new quantity. The two commands are not
    // one transaction: if Redis failed between them, the cart would only lack its expiry until the next change.
    public int addQuantity(UUID userId, UUID productId, int quantity) {
        Long newQuantity = hashOperations.increment(key(userId), productId.toString(), quantity);
        renewExpiry(userId);
        return newQuantity.intValue();
    }

    // HSET, then EXPIRE
    public void setQuantity(UUID userId, UUID productId, int quantity) {
        hashOperations.put(key(userId), productId.toString(), Integer.toString(quantity));
        renewExpiry(userId);
    }

    // HDEL. Redis deletes a hash when its last field goes, so an emptied cart leaves no key behind
    public void removeProduct(UUID userId, UUID productId) {
        hashOperations.delete(key(userId), productId.toString());
        renewExpiry(userId);
    }

    // DEL
    public void deleteCart(UUID userId) {
        redisTemplate.delete(key(userId));
    }

    private void renewExpiry(UUID userId) {
        redisTemplate.expire(key(userId), timeToLive);
    }
}
