package com.ledgerline.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class RequestHasherTest {

    @Test
    void sameInputProducesSameHash() {
        String a = RequestHasher.sha256Hex("key1|desc|acct:DEBIT:100");
        String b = RequestHasher.sha256Hex("key1|desc|acct:DEBIT:100");
        assertEquals(a, b);
    }

    @Test
    void differentInputProducesDifferentHash() {
        String a = RequestHasher.sha256Hex("key1|desc|acct:DEBIT:100");
        String b = RequestHasher.sha256Hex("key1|desc|acct:DEBIT:101");
        assertNotEquals(a, b);
    }

    @Test
    void hashIsSixtyFourHexChars() {
        String hash = RequestHasher.sha256Hex("anything");
        assertEquals(64, hash.length());
        assertEquals(hash, hash.toLowerCase());
    }
}
