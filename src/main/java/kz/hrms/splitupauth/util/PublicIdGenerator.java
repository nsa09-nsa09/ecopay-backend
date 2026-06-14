package kz.hrms.splitupauth.util;

import java.security.SecureRandom;

/**
 * URL-safe public identifier used to look up user profiles without exposing
 * the numeric primary key. 12 chars from the base62 alphabet (~71 bits of
 * entropy) is plenty for the user base size and stays comfortably under the
 * column's VARCHAR(16) limit so future variations still fit.
 */
public final class PublicIdGenerator {

    private static final char[] ALPHABET =
            "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
                    .toCharArray();
    private static final int LENGTH = 12;
    private static final SecureRandom RANDOM = new SecureRandom();

    private PublicIdGenerator() {
    }

    public static String generate() {
        char[] buf = new char[LENGTH];
        for (int i = 0; i < LENGTH; i++) {
            buf[i] = ALPHABET[RANDOM.nextInt(ALPHABET.length)];
        }
        return new String(buf);
    }
}
