/*
 * Forge: Play Magic: the Gathering.
 * Copyright (C) 2011  Forge Team
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
package forge.util;

import java.security.SecureRandom;
import java.util.Random;

/**
 * <p>
 * MyRandom class.<br>
 * Preferably all Random numbers should be retrieved using this wrapper class
 * </p>
 * 
 * @author Forge
 * @version $Id$
 */
public class MyRandom {
    /** Constant <code>random</code>. */
    private static Random random = new SecureRandom();

    static {
        // Deterministic-sim hook: -Dforge.rngSeed=<long> installs a seeded java.util.Random in place of
        // SecureRandom so AI-vs-AI runs are reproducible (used to PROVE engine/JVM-flag changes are
        // behavior-neutral: same seed + single sim thread => bit-identical games). No effect on normal
        // play or iOS (property unset => SecureRandom).
        final String seedProp = System.getProperty("forge.rngSeed");
        if (seedProp != null) {
            try {
                random = new CountingRandom(Long.parseLong(seedProp.trim()));
            } catch (NumberFormatException e) {
                // malformed seed -> keep SecureRandom
            }
        }
    }

    // Kept determinism-debug toolkit (gated, seeded-only, off by default): counts every underlying draw;
    // -Dforge.rngTrace prints the Forge caller of each draw and -Dforge.rngStack prints the full forge.* call
    // stack, so two same-seed runs' draw-streams can be diffed to localize a variable-draw seed-leak (the non-RNG
    // fork that drifts a later shuffle/choice). The reusable tool for run-to-run determinism hunts; sibling of
    // -Dforge.dumpGameLogGame / -Dforge.assertStaticMemo / -Dforge.tokTrace. NO effect in normal play / iOS:
    // CountingRandom is only installed when -Dforge.rngSeed is set, so production uses a plain SecureRandom.
    public static final java.util.concurrent.atomic.AtomicLong DRAW_COUNT = new java.util.concurrent.atomic.AtomicLong();
    private static final boolean RNG_TRACE = System.getProperty("forge.rngTrace") != null;
    private static final boolean RNG_STACK = System.getProperty("forge.rngStack") != null;
    private static final class CountingRandom extends Random {
        private static final long serialVersionUID = 1L;
        CountingRandom(final long seed) { super(seed); }
        @Override
        protected int next(final int bits) {
            DRAW_COUNT.incrementAndGet();
            if (RNG_TRACE) {
                String caller = "?";
                for (final StackTraceElement e : Thread.currentThread().getStackTrace()) {
                    final String cn = e.getClassName();
                    if (cn.startsWith("java.") || cn.startsWith("jdk.") || cn.contains("MyRandom")
                            || cn.equals("forge.util.Aggregates")) {
                        continue;
                    }
                    caller = cn + "." + e.getMethodName() + ":" + e.getLineNumber();
                    break;
                }
                System.out.println("[RNGTRACE] " + caller);
            }
            if (RNG_STACK) {
                final StringBuilder sb = new StringBuilder("[RNGSTACK]");
                for (final StackTraceElement e : Thread.currentThread().getStackTrace()) {
                    final String cn = e.getClassName();
                    if (cn.startsWith("forge.") && !cn.contains("MyRandom")) {
                        sb.append(' ').append(cn).append(':').append(e.getLineNumber());
                    }
                }
                System.out.println(sb.toString());
            }
            return super.next(bits);
        }
    }

    /**
     * <p>
     * percentTrue.<br>
     * If percent is like 30, then 30% of the time it will be true.
     * </p>
     * 
     * @param percent an int.
     * @return a boolean.
     */
    public static boolean percentTrue(final int percent) {
        return percent > MyRandom.getRandom().nextInt(100);
    }

    /**
     * Gets the random.
     * 
     * @return the random
     */
    public static Random getRandom() {
        return MyRandom.random;
    }

    /**
     * Sets the random provider. Used for deterministic simulation.
     * @param random the random
     */
    public static void setRandom(Random random) {
        MyRandom.random = random;
    }

    public static int[] splitIntoRandomGroups(final int value, final int numGroups) {
        int[] groups = new int[numGroups];
        
        for (int i = 0; i < value; i++) {
            groups[random.nextInt(numGroups)]++;
        }

        return groups;
    }
}
