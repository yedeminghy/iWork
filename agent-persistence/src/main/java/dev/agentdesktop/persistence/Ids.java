package dev.agentdesktop.persistence;

import java.math.BigInteger;
import java.security.SecureRandom;
import java.time.Clock;

public final class Ids {
    private static final char[] CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private Ids() {}

    public static String newId(String prefix) {
        return prefix + "_" + ulid(Clock.systemUTC().millis());
    }

    static String ulid(long timestamp) {
        byte[] value = new byte[16];
        value[0] = (byte) (timestamp >>> 40);
        value[1] = (byte) (timestamp >>> 32);
        value[2] = (byte) (timestamp >>> 24);
        value[3] = (byte) (timestamp >>> 16);
        value[4] = (byte) (timestamp >>> 8);
        value[5] = (byte) timestamp;
        byte[] randomness = new byte[10];
        RANDOM.nextBytes(randomness);
        System.arraycopy(randomness, 0, value, 6, randomness.length);

        BigInteger number = new BigInteger(1, value);
        char[] encoded = new char[26];
        for (int i = encoded.length - 1; i >= 0; i--) {
            encoded[i] = CROCKFORD[number.and(BigInteger.valueOf(31)).intValue()];
            number = number.shiftRight(5);
        }
        return new String(encoded);
    }
}

