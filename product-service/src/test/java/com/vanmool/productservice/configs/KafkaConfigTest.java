package com.vanmool.productservice.configs;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.util.backoff.FixedBackOff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KafkaConfigTest {
    // How the listener's failure arrives at the error handler
    private static ListenerExecutionFailedException listenerFailed(Exception cause) {
        return new ListenerExecutionFailedException("Listener method failed", cause);
    }

    @Test
    void databaseUnreachable_isTriedEvery5s_forAsLongAsItLasts() {
        // Arrange: no connection for the transaction, and a connection lost during the work
        Exception noConnection = listenerFailed(new CannotCreateTransactionException("Could not open JPA EntityManager for transaction"));
        Exception connectionLost = listenerFailed(new DataAccessResourceFailureException("Unable to acquire JDBC Connection"));

        // Act & Assert
        for (Exception failure : new Exception[] {noConnection, connectionLost}) {
            FixedBackOff backOff = (FixedBackOff) KafkaConfig.backOffFor(failure);
            assertEquals(5_000, backOff.getInterval());
            assertEquals(FixedBackOff.UNLIMITED_ATTEMPTS, backOff.getMaxAttempts());
        }
    }

    @Test
    void otherFailures_areTried10Times2sApart_thenGoToTheDeadLetterTopic() {
        // Act
        FixedBackOff backOff = (FixedBackOff) KafkaConfig.backOffFor(listenerFailed(new IllegalStateException("a bug")));

        // Assert
        assertEquals(2_000, backOff.getInterval());
        assertEquals(10, backOff.getMaxAttempts());
    }

    @Test
    void theDatabaseFailureIsFound_howeverDeeplyItIsWrapped() {
        assertTrue(KafkaConfig.isDatabaseUnavailable(listenerFailed(new RuntimeException("wrapped",
                new DataAccessResourceFailureException("Communications link failure")))));
        assertFalse(KafkaConfig.isDatabaseUnavailable(listenerFailed(new RuntimeException("no database involved"))));
    }
}
