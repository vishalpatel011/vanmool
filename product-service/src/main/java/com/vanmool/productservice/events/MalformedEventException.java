package com.vanmool.productservice.events;

// A message that is valid JSON but not a usable event (a missing or invalid field). Retrying cannot fix it, so the
// error handler sends it straight to the dead-letter topic
public class MalformedEventException extends RuntimeException {
    public MalformedEventException(String message) {
        super(message);
    }
}
