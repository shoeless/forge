package java.util;

/**
 * Minimal RFC 4648 Base64 for MobiVM's Java-7-era runtime (no java.util.Base64), supplied the
 * same way as the java.nio.file classes. Covers the surface Forge and its libraries link:
 * basic + URL codecs, optional padding, encodeToString/decode(String).
 */
public final class Base64 {
    private static final char[] BASIC =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();
    private static final char[] URL =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_".toCharArray();

    private static final Encoder BASIC_ENCODER = new Encoder(BASIC, true);
    private static final Encoder URL_ENCODER = new Encoder(URL, true);
    private static final Decoder BASIC_DECODER = new Decoder(false);
    private static final Decoder URL_DECODER = new Decoder(true);

    private Base64() {
    }

    public static Encoder getEncoder() {
        return BASIC_ENCODER;
    }

    public static Encoder getUrlEncoder() {
        return URL_ENCODER;
    }

    public static Decoder getDecoder() {
        return BASIC_DECODER;
    }

    public static Decoder getUrlDecoder() {
        return URL_DECODER;
    }

    public static final class Encoder {
        private final char[] alphabet;
        private final boolean padded;

        private Encoder(char[] alphabet, boolean padded) {
            this.alphabet = alphabet;
            this.padded = padded;
        }

        public Encoder withoutPadding() {
            return padded ? new Encoder(alphabet, false) : this;
        }

        public byte[] encode(byte[] src) {
            return encodeToString(src).getBytes();
        }

        public String encodeToString(byte[] src) {
            StringBuilder sb = new StringBuilder(((src.length + 2) / 3) * 4);
            int i = 0;
            for (; i + 2 < src.length; i += 3) {
                int n = ((src[i] & 0xff) << 16) | ((src[i + 1] & 0xff) << 8) | (src[i + 2] & 0xff);
                sb.append(alphabet[(n >>> 18) & 0x3f]).append(alphabet[(n >>> 12) & 0x3f])
                        .append(alphabet[(n >>> 6) & 0x3f]).append(alphabet[n & 0x3f]);
            }
            int rem = src.length - i;
            if (rem == 1) {
                int n = (src[i] & 0xff) << 16;
                sb.append(alphabet[(n >>> 18) & 0x3f]).append(alphabet[(n >>> 12) & 0x3f]);
                if (padded) sb.append("==");
            } else if (rem == 2) {
                int n = ((src[i] & 0xff) << 16) | ((src[i + 1] & 0xff) << 8);
                sb.append(alphabet[(n >>> 18) & 0x3f]).append(alphabet[(n >>> 12) & 0x3f])
                        .append(alphabet[(n >>> 6) & 0x3f]);
                if (padded) sb.append('=');
            }
            return sb.toString();
        }
    }

    public static final class Decoder {
        private final boolean url;

        private Decoder(boolean url) {
            this.url = url;
        }

        public byte[] decode(String src) {
            return decode(src.getBytes());
        }

        public byte[] decode(byte[] src) {
            int len = src.length;
            while (len > 0 && src[len - 1] == '=') len--; //padding is optional on decode
            int outLen = (len / 4) * 3 + (len % 4 == 2 ? 1 : len % 4 == 3 ? 2 : 0);
            if (len % 4 == 1) throw new IllegalArgumentException("truncated Base64 input");
            byte[] out = new byte[outLen];
            int o = 0, buf = 0, bits = 0;
            for (int i = 0; i < len; i++) {
                buf = (buf << 6) | sextet(src[i]);
                bits += 6;
                if (bits >= 8) {
                    bits -= 8;
                    out[o++] = (byte) ((buf >>> bits) & 0xff);
                }
            }
            return out;
        }

        private int sextet(byte c) {
            if (c >= 'A' && c <= 'Z') return c - 'A';
            if (c >= 'a' && c <= 'z') return c - 'a' + 26;
            if (c >= '0' && c <= '9') return c - '0' + 52;
            if (url) {
                if (c == '-') return 62;
                if (c == '_') return 63;
            } else {
                if (c == '+') return 62;
                if (c == '/') return 63;
            }
            throw new IllegalArgumentException("illegal Base64 character: " + (char) c);
        }
    }
}
