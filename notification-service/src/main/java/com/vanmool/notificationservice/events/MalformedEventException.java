package com.vanmool.notificationservice.events;

// A message that is valid JSON but not a usable event (a missing field). Retrying cannot fix it, so the error handler
// sends it straight to the dead-letter topic
public class MalformedEventException extends RuntimeException {
    public MalformedEventException(String message) {
        super(message);
    }
}
