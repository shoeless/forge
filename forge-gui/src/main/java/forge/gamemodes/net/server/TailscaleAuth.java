package forge.gamemodes.net.server;

import forge.util.IHasForgeLog;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Supplies a bearer token for the Tailscale Cloud API from whichever credential the user
 * configured, hiding the credential mechanics from callers ({@link TailscalePeerResolver}).
 *
 * <p>Supported credentials, in recommended order:</p>
 * <ol>
 *   <li><b>OAuth client</b> (client ID + client secret) — created in the Tailscale admin console
 *       under Settings &gt; OAuth clients with the {@code devices:core} Read scope. OAuth clients
 *       never expire (unlike API access tokens, which die after ≤90 days and then 401 forever —
 *       the original silent-failure mode of Tailscale discovery). The client-credentials grant is
 *       exchanged at {@code https://api.tailscale.com/api/v2/oauth/token} for a 1-hour access
 *       token, which is cached and refreshed a minute before expiry.</li>
 *   <li><b>Legacy API access token</b> ({@code tskey-api-…}) — used directly as the bearer token.
 *       Supported so existing users keep working, but it expires within 90 days.</li>
 * </ol>
 *
 * <p>New credential types (e.g. a proxy that mints tokens) only need another branch in
 * {@link #getBearerToken()}.</p>
 */
public class TailscaleAuth implements IHasForgeLog {
    private static final String OAUTH_TOKEN_URL = "https://api.tailscale.com/api/v2/oauth/token";
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 5000;
    /** Refresh the OAuth access token this long before its actual expiry. */
    private static final long EXPIRY_SLACK_MS = 60_000;
    /** Don't hammer the token endpoint after a failure; retry at most once per interval. */
    private static final long FAILURE_RETRY_MS = 60_000;

    private String clientId;
    private String clientSecret;

    private String cachedToken;
    private long tokenExpiryMs = 0;
    private long lastFailureMs = 0;
    private boolean failureLogged = false;

    /**
     * Configure credentials. Either an OAuth client (id + secret), or a legacy
     * {@code tskey-api-…} access token passed as {@code secret} with a null/empty id.
     */
    public synchronized void setCredentials(final String id, final String secret) {
        this.clientId = id == null ? "" : id.trim();
        this.clientSecret = secret == null ? "" : secret.trim();
        // Invalidate any cached token from prior credentials
        this.cachedToken = null;
        this.tokenExpiryMs = 0;
        this.lastFailureMs = 0;
        this.failureLogged = false;
    }

    /** True when any credential is configured. */
    public synchronized boolean isConfigured() {
        return clientSecret != null && !clientSecret.isEmpty();
    }

    /**
     * True when the configured credential is a legacy {@code tskey-api-…} access token
     * (as opposed to an OAuth client). Used for credential-specific error messages.
     */
    public synchronized boolean isLegacyToken() {
        return clientSecret != null && clientSecret.startsWith("tskey-api-");
    }

    /**
     * Returns a bearer token for the Tailscale API, or null if unconfigured or the
     * credential is currently rejected. Never throws.
     */
    public synchronized String getBearerToken() {
        if (!isConfigured()) {
            return null;
        }

        // Legacy API access token: use as-is, regardless of any (stale) client ID also saved —
        // a tskey-api token can never be a valid OAuth client secret, so routing it into the
        // OAuth exchange would only produce a misleading failure. (These tokens expire
        // server-side within 90 days; the resolver logs an actionable message on 401.)
        if (isLegacyToken()) {
            return clientSecret;
        }

        final long now = System.currentTimeMillis();
        if (cachedToken != null && now < tokenExpiryMs - EXPIRY_SLACK_MS) {
            return cachedToken;
        }
        if (now - lastFailureMs < FAILURE_RETRY_MS) {
            return null; // recent failure; don't hammer the token endpoint
        }
        return exchangeClientCredentials(now);
    }

    /**
     * Drops the cached access token so the next {@link #getBearerToken()} re-exchanges.
     * Called by the resolver when the API answers 401 with a token we thought was valid.
     */
    public synchronized void invalidateToken() {
        cachedToken = null;
        tokenExpiryMs = 0;
    }

    /**
     * Records an API-side rejection (401) so the exchange honors the failure backoff instead of
     * happily re-minting a token every refresh cycle that the API will reject again. Without this,
     * a revoked credential produced an endless mint-then-401 loop whose only log signature was a
     * healthy-looking "obtained token" line.
     */
    public synchronized void noteApiRejection() {
        lastFailureMs = System.currentTimeMillis();
    }

    /** OAuth 2.0 client-credentials grant against the Tailscale token endpoint. */
    private String exchangeClientCredentials(final long now) {
        HttpURLConnection conn = null;
        try {
            final String form = "client_id=" + URLEncoder.encode(clientId, "UTF-8")
                    + "&client_secret=" + URLEncoder.encode(clientSecret, "UTF-8");
            final byte[] body = form.getBytes(StandardCharsets.UTF_8);

            conn = (HttpURLConnection) new URL(OAUTH_TOKEN_URL).openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            conn.setDoOutput(true);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body);
            }

            final int code = conn.getResponseCode();
            if (code != 200) {
                lastFailureMs = now;
                if (!failureLogged) {
                    netLog.warn("TailscaleAuth: OAuth token exchange failed (HTTP {}). Check the "
                            + "OAuth client ID/secret in Tailscale Setup — recreate the client in the "
                            + "Tailscale admin console (Settings > OAuth clients, devices Read scope) "
                            + "if it was revoked.", code);
                    failureLogged = true;
                }
                return null;
            }

            final String json = readBody(conn);
            final String token = extractStringField(json, "access_token");
            final long expiresInSec = extractLongField(json, "expires_in", 3600);
            if (token == null || token.isEmpty()) {
                lastFailureMs = now;
                netLog.warn("TailscaleAuth: token endpoint returned 200 but no access_token");
                return null;
            }

            cachedToken = token;
            tokenExpiryMs = now + expiresInSec * 1000;
            if (failureLogged) {
                netLog.info("TailscaleAuth: OAuth token exchange succeeded again");
                failureLogged = false;
            } else {
                // debug, not info: on a rejected-credential loop this would otherwise print a
                // healthy-looking line every refresh while discovery is actually broken
                netLog.debug("TailscaleAuth: obtained OAuth access token (expires in {}s)", expiresInSec);
            }
            return cachedToken;
        } catch (final Exception e) {
            lastFailureMs = now;
            if (!failureLogged) {
                netLog.warn("TailscaleAuth: OAuth token exchange error: {}", e.getMessage());
                failureLogged = true;
            }
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static String readBody(final HttpURLConnection conn) throws Exception {
        final StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
        }
        return sb.toString();
    }

    private static String extractStringField(final String json, final String fieldName) {
        final int fieldStart = json.indexOf("\"" + fieldName + "\"");
        if (fieldStart < 0) {
            return null;
        }
        final int colonPos = json.indexOf(":", fieldStart + fieldName.length() + 2);
        if (colonPos < 0) {
            return null;
        }
        int i = colonPos + 1;
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) {
            i++;
        }
        if (i >= json.length() || json.charAt(i) != '"') {
            return null;
        }
        final int valueEnd = json.indexOf("\"", i + 1);
        return valueEnd < 0 ? null : json.substring(i + 1, valueEnd);
    }

    private static long extractLongField(final String json, final String fieldName, final long defaultVal) {
        final int fieldStart = json.indexOf("\"" + fieldName + "\"");
        if (fieldStart < 0) {
            return defaultVal;
        }
        final int colonPos = json.indexOf(":", fieldStart + fieldName.length() + 2);
        if (colonPos < 0) {
            return defaultVal;
        }
        int i = colonPos + 1;
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) {
            i++;
        }
        final int start = i;
        while (i < json.length() && Character.isDigit(json.charAt(i))) {
            i++;
        }
        if (i == start) {
            return defaultVal;
        }
        try {
            return Long.parseLong(json.substring(start, i));
        } catch (final NumberFormatException e) {
            return defaultVal;
        }
    }
}
