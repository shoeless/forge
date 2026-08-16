package forge.util;

import java.io.Serializable;
import java.util.Comparator;

/**
 * Drop-in replacement for {@link String#CASE_INSENSITIVE_ORDER} with an ASCII fast path:
 * identical ordering for every input pair, but ASCII characters skip the per-character
 * {@code Character.toUpperCase}/{@code toLowerCase} calls; non-ASCII falls back to the
 * exact JDK comparison sequence.
 */
public final class CaseInsensitiveOrder implements Comparator<String>, Serializable {
    private static final long serialVersionUID = 1L;

    public static final CaseInsensitiveOrder INSTANCE = new CaseInsensitiveOrder();

    private CaseInsensitiveOrder() {
    }

    @Override
    public int compare(String s1, String s2) {
        final int n1 = s1.length();
        final int n2 = s2.length();
        final int min = Math.min(n1, n2);
        for (int i = 0; i < min; i++) {
            char c1 = s1.charAt(i);
            char c2 = s2.charAt(i);
            if (c1 == c2) {
                continue;
            }
            if (c1 < 128 && c2 < 128) {
                if (c1 >= 'A' && c1 <= 'Z') {
                    c1 += 32;
                }
                if (c2 >= 'A' && c2 <= 'Z') {
                    c2 += 32;
                }
                if (c1 != c2) {
                    return c1 - c2;
                }
            } else {
                c1 = Character.toUpperCase(c1);
                c2 = Character.toUpperCase(c2);
                if (c1 != c2) {
                    c1 = Character.toLowerCase(c1);
                    c2 = Character.toLowerCase(c2);
                    if (c1 != c2) {
                        return c1 - c2;
                    }
                }
            }
        }
        return n1 - n2;
    }

    private Object readResolve() {
        return INSTANCE;
    }
}
