/*
 * Copyright © 2025 Trevin Beattie
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.xmission.trevin.android.notes.util;

import static org.junit.Assert.*;

import org.apache.commons.lang3.RandomStringUtils;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Random;

/**
 * Tests for encrypting and decrypting private strings
 *
 * @author Trevin Beattie
 */
// To Do: Add tests for hasPassword, checkPassword, storePassword, and
// removePassword.  All of these currently require the MockNoteRepository.
public class StringEncryptionTests {

    /** Random number generator for some tests */
    final Random RAND = new Random();
    /** Random string generator for use in the tests */
    final RandomStringUtils STRING_GEN = RandomStringUtils.insecure();

    /**
     * Verify the Password-Based Key Derivation Function.
     * For a given password, this ought to result in the same key
     * regardless of the Android API level.
     */
    @Test
    public void testGenerateKey() throws GeneralSecurityException {

        // Fixed password and salt to check against a manually generated key.
        // The password includes extended UTF-8 characters in order to verify
        // that we're using the algorithm which looks at all available bits
        // in Unicode characters, in compliance with PCKS #5.
        final String testPassword = "FreeBSD 😈 ⅩⅢ½";
        final byte[] testSalt = {
                (byte) 0xc8, (byte) 0xd8, (byte) 0x24, (byte) 0x7b,
                (byte) 0xf2, (byte) 0x77, (byte) 0x61, (byte) 0xa1,
                (byte) 0x34, (byte) 0xcf, (byte) 0x35, (byte) 0xd7,
                (byte) 0xd0, (byte) 0xfa, (byte) 0x7b, (byte) 0x37,
                (byte) 0x60, (byte) 0xfe, (byte) 0x61, (byte) 0xf4,
                (byte) 0x6a, (byte) 0x03, (byte) 0xe1, (byte) 0xba,
                (byte) 0x5c, (byte) 0x4c, (byte) 0x3e, (byte) 0x46,
                (byte) 0x94, (byte) 0x1d, (byte) 0x12, (byte) 0xea };
        final byte[] expectedKey = {
                (byte) 0x65, (byte) 0xbe, (byte) 0x4b, (byte) 0x34,
                (byte) 0xad, (byte) 0x17, (byte) 0xd3, (byte) 0x00,
                (byte) 0xe9, (byte) 0x85, (byte) 0xc8, (byte) 0xf4,
                (byte) 0x8a, (byte) 0xb7, (byte) 0xad, (byte) 0xea,
                (byte) 0x5a, (byte) 0xb6, (byte) 0xbb, (byte) 0xae,
                (byte) 0x63, (byte) 0x5d, (byte) 0xc2, (byte) 0xe5,
                (byte) 0xc4, (byte) 0xb0, (byte) 0x6d, (byte) 0xc9,
                (byte) 0x31, (byte) 0x40, (byte) 0x26, (byte) 0x41
        };

        StringEncryption se = StringEncryption.holdGlobalEncryption();
        try {
            se.setPassword(testPassword.toCharArray());
            se.setSalt(testSalt);
            assertArrayEquals(expectedKey, se.getKey());
        } finally {
            StringEncryption.releaseGlobalEncryption();
        }

    }

    /**
     * Test encrypting and decrypting a string.  This doesn't verify the
     * encrypted bytes since we're not checking for a specific key; we
     * only care that the decrypted string matches the original.
     */
    @Test
    public void testStringEncryption() throws GeneralSecurityException {

        String password = STRING_GEN.nextAlphabetic(5, 10);
        // Make sure the text we're encrypting uses
        // a mix of different Unicode groups.
        final String originalText = "Garçon, ένα μπουκάλι вина よろしければ 𓇭🍷";

        StringEncryption se = StringEncryption.holdGlobalEncryption();
        try {
            se.setPassword(password.toCharArray());
            se.addSalt();
            byte[] encrypted = se.encrypt(originalText);
            // The encryption should be at least as long as the plain text
            assertTrue("Encrypted bytes are shorter than the plain text",
                    encrypted.length >= originalText.getBytes(
                            StandardCharsets.UTF_8).length);
            String decryptedText = se.decrypt(encrypted);
            assertEquals("Decrypted text", originalText, decryptedText);
        } finally {
            StringEncryption.releaseGlobalEncryption();
        }

    }

    /**
     * Test encrypting and decrypting a byte array.  This doesn't verify the
     * encrypted bytes since we're not checking for a specific key; we
     * only care that the decrypted byte array matches the original.
     */
    @Test
    public void testByteEncryption() throws GeneralSecurityException {

        String password = STRING_GEN.nextAlphabetic(5, 10);
        byte[] originalBytes = new byte[8 + RAND.nextInt(9)];

        StringEncryption se = StringEncryption.holdGlobalEncryption();
        try {
            se.setPassword(password.toCharArray());
            se.addSalt();
            byte[] encrypted = se.encrypt(originalBytes);
            // The encryption should be at least as long as the original bytes
            assertTrue("Encrypted bytes are shorter than the plain bytes",
                    encrypted.length >= originalBytes.length);
            byte[] decryptedBytes = se.decryptBytes(encrypted);
            assertArrayEquals("Decrypted byte array",
                    originalBytes, decryptedBytes);
            assertArrayEquals("Decrypted bytes", originalBytes, decryptedBytes);
        } finally {
            StringEncryption.releaseGlobalEncryption();
        }

    }

}
