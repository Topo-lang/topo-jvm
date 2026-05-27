package dev.topo.transform.pass;

/**
 * SipHash-2-4-128 PRF, the same primitive
 * {@code topo-llvm/lib/Transforms/SymbolObfuscator.cpp} uses via
 * {@code llvm::getSipHash_2_4_128}.
 *
 * <p>The implementation follows the SipHash reference
 * (Aumasson &amp; Bernstein, 2012). With {@code c=2} compression rounds
 * and {@code d=4} finalization rounds, output truncated to 128 bits.
 *
 * <p>Why an in-tree implementation rather than a third-party library:
 * the obfuscator must produce the exact same hex digest as the LLVM
 * side for a shared input + key (the cross-backend parity gate
 * compares them), so the algorithm is pinned by name and revision
 * here. Pulling in {@code com.google.guava} or another large dep just
 * for one PRF would expand the transform-jar surface area
 * disproportionately to the value.
 *
 * <p>The class is package-private — only {@link ObfuscationPass} (and
 * its tests) consume it; making it public would create a
 * cross-backend ABI surface neither side wants to commit to.
 */
final class SipHash {

    private SipHash() {}

    /**
     * Compute SipHash-2-4-128 over {@code message} keyed by
     * {@code key}.
     *
     * @param key     PRF key. Truncated or zero-padded to 16 bytes
     *                (mirrors LLVM's {@code computeHash} behaviour).
     * @param message the byte sequence to hash.
     * @return 16 bytes of output (128 bits).
     */
    static byte[] hash128(byte[] key, byte[] message) {
        // Derive 16-byte key (truncate or zero-pad)
        byte[] k = new byte[16];
        int copyLen = Math.min(key.length, 16);
        System.arraycopy(key, 0, k, 0, copyLen);

        long k0 = readLongLE(k, 0);
        long k1 = readLongLE(k, 8);

        long v0 = k0 ^ 0x736f6d6570736575L;
        long v1 = k1 ^ 0x646f72616e646f6dL;
        long v2 = k0 ^ 0x6c7967656e657261L;
        long v3 = k1 ^ 0x7465646279746573L;
        // 128-bit output tweak per the SipHash reference
        v1 ^= 0xee;

        int len = message.length;
        int end = len - (len & 7);
        int i = 0;
        long[] state = {v0, v1, v2, v3};
        for (; i < end; i += 8) {
            long m = readLongLE(message, i);
            state[3] ^= m;
            sipRound(state);
            sipRound(state);
            state[0] ^= m;
        }

        // Final block: remaining bytes + length byte
        long last = ((long) (len & 0xff)) << 56;
        switch (len - i) {
            case 7: last |= ((long) (message[i + 6] & 0xff)) << 48;
            case 6: last |= ((long) (message[i + 5] & 0xff)) << 40;
            case 5: last |= ((long) (message[i + 4] & 0xff)) << 32;
            case 4: last |= ((long) (message[i + 3] & 0xff)) << 24;
            case 3: last |= ((long) (message[i + 2] & 0xff)) << 16;
            case 2: last |= ((long) (message[i + 1] & 0xff)) << 8;
            case 1: last |= ((long) (message[i] & 0xff));
            case 0: break;
            default: throw new AssertionError();
        }
        state[3] ^= last;
        sipRound(state);
        sipRound(state);
        state[0] ^= last;

        // Finalization for 128-bit output: tweak v2 ^= 0xee, then 4 rounds
        state[2] ^= 0xee;
        sipRound(state);
        sipRound(state);
        sipRound(state);
        sipRound(state);
        long out0 = state[0] ^ state[1] ^ state[2] ^ state[3];

        // Second 8 bytes: re-tweak and 4 more rounds
        state[1] ^= 0xdd;
        sipRound(state);
        sipRound(state);
        sipRound(state);
        sipRound(state);
        long out1 = state[0] ^ state[1] ^ state[2] ^ state[3];

        byte[] out = new byte[16];
        writeLongLE(out, 0, out0);
        writeLongLE(out, 8, out1);
        return out;
    }

    private static void sipRound(long[] s) {
        s[0] += s[1];
        s[1] = Long.rotateLeft(s[1], 13);
        s[1] ^= s[0];
        s[0] = Long.rotateLeft(s[0], 32);
        s[2] += s[3];
        s[3] = Long.rotateLeft(s[3], 16);
        s[3] ^= s[2];
        s[0] += s[3];
        s[3] = Long.rotateLeft(s[3], 21);
        s[3] ^= s[0];
        s[2] += s[1];
        s[1] = Long.rotateLeft(s[1], 17);
        s[1] ^= s[2];
        s[2] = Long.rotateLeft(s[2], 32);
    }

    private static long readLongLE(byte[] buf, int off) {
        return ((long) (buf[off] & 0xff))
            | (((long) (buf[off + 1] & 0xff)) << 8)
            | (((long) (buf[off + 2] & 0xff)) << 16)
            | (((long) (buf[off + 3] & 0xff)) << 24)
            | (((long) (buf[off + 4] & 0xff)) << 32)
            | (((long) (buf[off + 5] & 0xff)) << 40)
            | (((long) (buf[off + 6] & 0xff)) << 48)
            | (((long) (buf[off + 7] & 0xff)) << 56);
    }

    private static void writeLongLE(byte[] buf, int off, long v) {
        buf[off]     = (byte) (v);
        buf[off + 1] = (byte) (v >>> 8);
        buf[off + 2] = (byte) (v >>> 16);
        buf[off + 3] = (byte) (v >>> 24);
        buf[off + 4] = (byte) (v >>> 32);
        buf[off + 5] = (byte) (v >>> 40);
        buf[off + 6] = (byte) (v >>> 48);
        buf[off + 7] = (byte) (v >>> 56);
    }
}
