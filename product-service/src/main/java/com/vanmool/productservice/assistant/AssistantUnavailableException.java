package com.vanmool.productservice.assistant;

// The language model behind an assistant could not give an answer (its server is down or too slow, or the answer was
// not usable). Answered with 503: the client may try again.
public class AssistantUnavailableException extends RuntimeException {
    public AssistantUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
