package kz.hrms.splitupauth.exception;

/**
 * Operation cannot complete because the target resource is in a state that
 * conflicts with the request (e.g., deactivating a category that still has
 * active child services, or creating a second testimonial for the same user).
 * Mapped to HTTP 409 Conflict in {@link GlobalExceptionHandler}.
 */
public class ResourceConflictException extends RuntimeException {
    public ResourceConflictException(String message) {
        super(message);
    }
}
