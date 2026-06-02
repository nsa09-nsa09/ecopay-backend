package kz.hrms.splitupauth.service;

import kz.hrms.splitupauth.exception.TooManyRequestsException;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lightweight in-process sliding-window rate limiter for abuse-prone actions
 * (room create / join). Per-instance only — for a multi-instance deployment move
 * this to Redis. Keys are typically "action:userId".
 */
@Component
public class InMemoryRateLimiter {

    private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();

    /**
     * Records one hit for {@code key} and throws {@link TooManyRequestsException}
     * if more than {@code maxOps} hits occurred within the last {@code windowSeconds}.
     */
    public void check(String key, int maxOps, long windowSeconds, String message) {
        long now = System.currentTimeMillis();
        long windowMs = windowSeconds * 1000L;
        Deque<Long> dq = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (dq) {
            while (!dq.isEmpty() && now - dq.peekFirst() > windowMs) {
                dq.pollFirst();
            }
            if (dq.size() >= maxOps) {
                throw new TooManyRequestsException(message);
            }
            dq.addLast(now);
        }
    }
}
