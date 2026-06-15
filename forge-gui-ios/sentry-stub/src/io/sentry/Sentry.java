package io.sentry;

import io.sentry.protocol.SentryId;

/**
 * No-op stub of the Sentry facade for the RoboVM/iOS build.
 *
 * The real io.sentry:sentry SDK is excluded from the iOS module (it pulls in
 * java.util.function, an HTTP transport and background threads that do not link
 * on RoboVM), so every telemetry call in shared Forge code (BugReporter,
 * PlayerControllerHuman, Forge, SaveFileData, VCardDisplayArea) would otherwise
 * throw NoClassDefFoundError: io/sentry/Sentry at runtime and kill the game
 * thread. These no-ops carry the exact signatures of the methods actually
 * referenced so the precompiled bytecode links. Sentry is never initialised on
 * iOS; desktop builds use the real SDK and are unaffected.
 */
public final class Sentry {
    private Sentry() { }

    public static SentryId captureMessage(String message) { return new SentryId(); }
    public static SentryId captureException(Throwable throwable) { return new SentryId(); }
    public static SentryId captureException(Throwable throwable, Hint hint) { return new SentryId(); }
    public static void addBreadcrumb(String message) { }
    public static void setExtra(String key, String value) { }
    public static void configureScope(ScopeType scopeType, ScopeCallback callback) { }
}
