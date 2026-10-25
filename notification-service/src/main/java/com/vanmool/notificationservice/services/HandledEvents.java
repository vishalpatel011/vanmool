package com.vanmool.notificationservice.services;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

// The ids of events whose email was sent, in Redis (notification:event:{eventId}), kept for 7 days: Kafka delivers
// at least once, and a repeated event must not send a second email.
// Best effort: if Redis is unreachable, the event counts as not handled. One email too many is better than none.
@Slf4j
@Component
public class HandledEvents {
    private static final String KEY_PREFIX = "notification:event:";

    private final StringRedisTemplate redisTemplate;
    private final Duration timeToLive;

    public HandledEvents(StringRedisTemplate redisTemplate,
                         @Value("${notification.handled-events.time-to-live}") Duration timeToLive) {
        this.redisTemplate = redisTemplate;
        this.timeToLive = timeToLive;
    }

    public boolean contains(UUID eventId) {
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(KEY_PREFIX + eventId));
        } catch (DataAccessException e) {
            log.warn("Could not check event {} in Redis, handling it: {}", eventId, e.getMessage());
            return false;
        }
    }

    public void add(UUID eventId) {
        try {
            redisTemplate.opsForValue().set(KEY_PREFIX + eventId, "sent", timeToLive);
        } catch (DataAccessException e) {
            log.warn("Could not record event {} in Redis: {}", eventId, e.getMessage());
        }
    }
}
