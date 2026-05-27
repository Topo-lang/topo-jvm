package dev.topo.transform.pass;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins SipHash-2-4-128 against a published reference vector so any
 * drift in {@link SipHash} is caught immediately.
 *
 * <p>The expected bytes are also pinned by the LLVM-side
 * {@code SymbolObfuscatorTest.SipHashCrossBackendVector} test — both
 * sides must produce the same digest for the same input or the
 * cross-backend parity row {@code [obfuscation.hash_algorithm]} in
 * {@code scripts/audit/cross-backend-parity.toml} fails.
 */
class SipHashTest {

    @Test
    void referenceVectorMatches() {
        // Standard cross-backend parity input: key = bytes 0x00..0x0f,
        // message = bytes 0x00..0x0e (the same input the LLVM unit
        // test uses).
        byte[] key = new byte[16];
        for (int i = 0; i < 16; i++) key[i] = (byte) i;
        byte[] msg = new byte[15];
        for (int i = 0; i < 15; i++) msg[i] = (byte) i;

        byte[] out = SipHash.hash128(key, msg);

        // Verified against an independent Python reference
        // implementation of SipHash-2-4-128; the LLVM
        // SymbolObfuscatorTest.SipHashCrossBackendVector pins the
        // identical expected bytes.
        byte[] expected = new byte[] {
            (byte) 0x54, (byte) 0x93, (byte) 0xe9, (byte) 0x99,
            (byte) 0x33, (byte) 0xb0, (byte) 0xa8, (byte) 0x11,
            (byte) 0x7e, (byte) 0x08, (byte) 0xec, (byte) 0x0f,
            (byte) 0x97, (byte) 0xcf, (byte) 0xc3, (byte) 0xd9
        };
        assertArrayEquals(expected, out,
            "JVM SipHash-2-4-128 output drifted; cross-backend " +
            "parity with LLVM SymbolObfuscator is broken — see " +
            "scripts/audit/cross-backend-parity.toml row " +
            "[obfuscation.hash_algorithm].");
    }

    @Test
    void shortKeyZeroPaddedTo16Bytes() {
        // Short keys must be zero-padded to 16 bytes, matching the
        // LLVM computeHash behaviour (truncate or zero-pad). A
        // 5-byte key + 16-byte all-zero key produce different digests
        // (the first 5 bytes differ), but a 5-byte key vs the same
        // 5 bytes followed by 11 zero bytes must produce the same
        // digest.
        byte[] shortKey = "short".getBytes();
        byte[] paddedKey = new byte[16];
        System.arraycopy(shortKey, 0, paddedKey, 0, 5);
        byte[] msg = "abc".getBytes();

        assertArrayEquals(
            SipHash.hash128(shortKey, msg),
            SipHash.hash128(paddedKey, msg),
            "short key must be zero-padded, not error/random-padded");
    }

    @Test
    void emptyMessageProducesNonzeroDigest() {
        // Edge case: 0-byte message. The length byte (0) still gets
        // mixed into v3 so the output is well-defined and non-zero.
        byte[] out = SipHash.hash128(new byte[16], new byte[0]);
        assertEquals(16, out.length);
        boolean allZero = true;
        for (byte b : out) {
            if (b != 0) { allZero = false; break; }
        }
        assertFalse(allZero, "empty-message digest must not be all zeros");
    }
}
