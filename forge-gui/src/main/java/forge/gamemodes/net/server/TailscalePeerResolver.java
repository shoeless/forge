package forge.gamemodes.net.server;

import forge.util.IHasForgeLog;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Discovers Tailscale peer IP addresses for cross-network game discovery.
 * Used by {@link ServerBroadcaster} to send unicast discovery packets to
 * Tailscale peers that can't be reached via LAN broadcast.
 *
 * <p>Three strategies, tried in order:</p>
 * <ol>
 *   <li><b>Tailscale Cloud API</b> ({@code https://api.tailscale.com/api/v2/tailnet/-/devices})
 *       — authenticated via {@link TailscaleAuth} (non-expiring OAuth client preferred; legacy
 *       API token supported). Returns all devices on the tailnet with their Tailscale IPs.
 *       Works from iOS since it's a standard HTTPS request.</li>
 *   <li><b>Tailscale local API</b> ({@code http://100.100.100.100/localapi/v0/status})
 *       — returns precise list of online peers. Works on macOS/Linux, blocked
 *       on iOS.</li>
 *   <li><b>/24 subnet scan</b> — sends to all 254 addresses in the host's
 *       Tailscale /24 subnet. Best-effort fallback for same-subnet peers.</li>
 * </ol>
 *
 * <p>Results are cached for 60 seconds.</p>
 */
public class TailscalePeerResolver implements IHasForgeLog {
    private static final String TAILSCALE_CLOUD_API_URL =
            "https://api.tailscale.com/api/v2/tailnet/-/devices?fields=default";
    private static final String TAILSCALE_LOCAL_API_URL =
            "http://100.100.100.100/localapi/v0/status";
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 5000;
    private static final long CACHE_DURATION_MS = 60000;

    private static final Pattern QUOTED_STRING = Pattern.compile("\"([^\"]+)\"");

    private final TailscaleAuth auth = new TailscaleAuth();
    private volatile List<InetAddress> cachedPeers = new ArrayList<>();
    private volatile long cacheTimestamp = 0;
    private final AtomicBoolean refreshInFlight = new AtomicBoolean(false);
    private boolean localApiFailed = false;
    private boolean cloudApiLoggedSuccess = false;
    private boolean cloudApiLoggedFailure = false;
    private boolean reverseDnsLogged = false;

    /**
     * Configure the Tailscale credential for cloud-based peer discovery: an OAuth client
     * (id + secret, devices Read scope) or a legacy {@code tskey-api-…} token as the secret.
     */
    public void setCredentials(final String clientId, final String clientSecret) {
        auth.setCredentials(clientId, clientSecret);
    }

    /**
     * Returns Tailscale peer IPv4 addresses to send discovery packets to. NEVER blocks: it
     * returns the current cached list immediately and, when the cache is stale, kicks a refresh
     * on a background thread. The caller is the 2-second LAN-broadcast loop, and guests prune a
     * host after 6s of silence — doing token/devices HTTP inline here (5s connect + 5s read,
     * twice, and DNS resolution unbounded by those timeouts) stalled LAN broadcasts long enough
     * to make the hosted game flicker out of every guest's discovered list once a minute.
     */
    public List<InetAddress> getOnlinePeers() {
        final long now = System.currentTimeMillis();
        if (now - cacheTimestamp >= CACHE_DURATION_MS && refreshInFlight.compareAndSet(false, true)) {
            final Thread t = new Thread(this::refreshPeers, "TailscalePeerRefresh");
            t.setDaemon(true);
            t.start();
        }
        return cachedPeers;
    }

    /** Runs on the background refresh thread; the only writer of cachedPeers/cacheTimestamp. */
    private void refreshPeers() {
        try {
            // Pre-gate on a Tailscale interface actually being present: without one there are no
            // peers to reach, no point probing the local API (a 2s connect stall on machines that
            // never installed Tailscale), and no point exchanging OAuth tokens.
            if (FServerManager.getTailscaleAddress() == null) {
                cachedPeers = new ArrayList<>();
                return;
            }

            // Try reverse DNS on own Tailscale IP for debugging (once)
            if (!reverseDnsLogged) {
                tryReverseDns();
                reverseDnsLogged = true;
            }

            List<InetAddress> peers = new ArrayList<>();

            // Strategy 1: Tailscale Cloud API (works on iOS, needs a configured credential)
            if (auth.isConfigured()) {
                peers = queryCloudApi();
            }

            // Strategy 2: Tailscale local API (blocked on iOS but works on desktop). Also runs
            // when a configured credential yields nothing — a broken credential must not disable
            // a strategy that needs no credential at all.
            if (peers.isEmpty()) {
                peers = queryLocalApi();
            }

            // Strategy 3: /24 subnet scan fallback
            if (peers.isEmpty()) {
                peers = generateSubnetPeers();
            }

            cachedPeers = peers;
        } catch (final Throwable t) {
            netLog.error("TailscalePeerResolver: peer refresh error: {}", t.getMessage());
        } finally {
            cacheTimestamp = System.currentTimeMillis();
            refreshInFlight.set(false);
        }
    }

    // ========================================================================
    // Strategy 1: Tailscale Cloud REST API
    // ========================================================================

    /**
     * Query the Tailscale Cloud API for all devices on the tailnet.
     * Returns Tailscale IPv4 addresses of all devices except self.
     *
     * <p>API endpoint: GET https://api.tailscale.com/api/v2/tailnet/-/devices</p>
     * <p>Auth: Bearer token supplied by {@link TailscaleAuth}</p>
     */
    private List<InetAddress> queryCloudApi() {
        List<InetAddress> peers = new ArrayList<>();
        final String bearer = auth.getBearerToken();
        if (bearer == null) {
            return peers; // credential missing/rejected — TailscaleAuth already logged why
        }
        HttpURLConnection conn = null;
        try {
            final URL url = new URL(TAILSCALE_CLOUD_API_URL);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Authorization", "Bearer " + bearer);

            final int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                if (responseCode == 401) {
                    // Credential dead (expired legacy token or revoked OAuth client). Drop the
                    // cached token AND record the rejection so the exchange honors its backoff —
                    // otherwise a revoked credential re-mints a doomed token every refresh while
                    // logging what looks like success.
                    auth.invalidateToken();
                    auth.noteApiRejection();
                    if (!cloudApiLoggedFailure) {
                        if (auth.isLegacyToken()) {
                            netLog.warn("TailscalePeerResolver: Tailscale rejected the API access token "
                                    + "(HTTP 401) — legacy tokens expire within 90 days. Switch to an "
                                    + "OAuth client in Tailscale Setup (admin console > Settings > "
                                    + "OAuth clients, devices Read scope).");
                        } else {
                            netLog.warn("TailscalePeerResolver: Tailscale rejected the OAuth token "
                                    + "(HTTP 401) — the OAuth client may have been revoked. Recreate it "
                                    + "in the Tailscale admin console (Settings > OAuth clients, devices "
                                    + "Read scope) and re-enter it in Tailscale Setup.");
                        }
                        cloudApiLoggedFailure = true;
                    }
                } else if (responseCode == 403) {
                    // Token is valid but lacks the devices Read scope. Keep it — scopes are fixed
                    // at client creation, so re-minting yields an identically-scoped token; an
                    // invalidate here caused a pointless mint-403 loop every refresh.
                    if (!cloudApiLoggedFailure) {
                        netLog.warn("TailscalePeerResolver: Tailscale refused the device list "
                                + "(HTTP 403) — the credential lacks the required scope. Recreate the "
                                + "OAuth client with the 'devices' READ scope (Tailscale admin console "
                                + "> Settings > OAuth clients) and re-enter it in Tailscale Setup.");
                        cloudApiLoggedFailure = true;
                    }
                } else if (!cloudApiLoggedFailure) {
                    netLog.info("TailscalePeerResolver: Cloud API returned HTTP {}", responseCode);
                    cloudApiLoggedFailure = true;
                }
                return peers;
            }

            peers = parseCloudDevices(readResponse(conn));

            if (!cloudApiLoggedSuccess) {
                netLog.info("TailscalePeerResolver: Cloud API found {} peer(s)", peers.size());
                cloudApiLoggedSuccess = true;
            }
            // reset the failure latch on EVERY success, so a later, different failure logs again
            cloudApiLoggedFailure = false;

        } catch (final Exception e) {
            if (!cloudApiLoggedFailure) {
                netLog.info("TailscalePeerResolver: Cloud API error: {}", e.getMessage());
                cloudApiLoggedFailure = true;
            }
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }

        return peers;
    }

    /**
     * Parse the Tailscale Cloud API response to extract peer IPv4 addresses.
     *
     * Expected structure:
     * <pre>
     * {
     *   "devices": [
     *     {
     *       "addresses": ["100.101.102.103", "fd7a:..."],
     *       "hostname": "my-tablet",
     *       "name": "my-tablet.example.ts.net",
     *       ...
     *     }
     *   ]
     * }
     * </pre>
     */
    private List<InetAddress> parseCloudDevices(final String json) {
        final List<InetAddress> peers = new ArrayList<>();

        // Get our own Tailscale IPs to exclude. Must use getAllAddressesForDiscovery: the
        // getAllLanAddresses variant skips tunnel interfaces (utun), so on iOS/macOS it never
        // contained our own Tailscale IP and the host uselessly unicast discovery to itself.
        final Set<String> selfIps = new HashSet<>();
        for (final String addr : FServerManager.getAllAddressesForDiscovery()) {
            if (isTailscaleIp(addr)) {
                selfIps.add(addr);
            }
        }

        // Find the "devices" array
        final int devicesStart = json.indexOf("\"devices\"");
        if (devicesStart < 0) {
            return peers;
        }
        final int arrayStart = json.indexOf("[", devicesStart);
        if (arrayStart < 0) {
            return peers;
        }
        final int arrayEnd = findMatchingBracket(json, arrayStart);
        if (arrayEnd < 0) {
            return peers;
        }

        // Iterate through device objects
        final String devicesArray = json.substring(arrayStart + 1, arrayEnd);
        int pos = 0;
        while (pos < devicesArray.length()) {
            final int objStart = devicesArray.indexOf("{", pos);
            if (objStart < 0) {
                break;
            }
            final int objEnd = findMatchingBrace(devicesArray, objStart);
            if (objEnd < 0) {
                break;
            }

            final String deviceObj = devicesArray.substring(objStart, objEnd + 1);

            // Extract addresses. Deliberately do NOT log device hostnames: the devices endpoint
            // returns every device on the tailnet (not just Forge players), hostnames routinely
            // embed personal names, and the NETWORK log tag persists to on-disk session files
            // that users share for bug reports — logging them each refresh would write a
            // recurring PII inventory of the whole tailnet to disk.
            final List<String> addresses = extractStringArray(deviceObj, "addresses");

            for (final String ip : addresses) {
                if (isIPv4(ip) && isTailscaleIp(ip) && !selfIps.contains(ip)) {
                    try {
                        peers.add(InetAddress.getByName(ip));
                    } catch (final Exception e) {
                        // Skip invalid addresses
                    }
                }
            }

            pos = objEnd + 1;
        }

        return peers;
    }

    // ========================================================================
    // Strategy 2: Tailscale Local API
    // ========================================================================

    private List<InetAddress> queryLocalApi() {
        List<InetAddress> peers = new ArrayList<>();
        HttpURLConnection conn = null;
        try {
            final URL url = new URL(TAILSCALE_LOCAL_API_URL);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(3000);
            conn.setRequestMethod("GET");

            final int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                if (!localApiFailed) {
                    netLog.info("TailscalePeerResolver: Local API returned HTTP {}, will try fallbacks", responseCode);
                    localApiFailed = true;
                }
                return peers;
            }

            peers = parseLocalApiPeers(readResponse(conn));

            if (localApiFailed) {
                netLog.info("TailscalePeerResolver: Local API now reachable");
                localApiFailed = false;
            }
            if (!peers.isEmpty()) {
                netLog.info("TailscalePeerResolver: Local API found {} online peer(s)", peers.size());
            }

        } catch (final Exception e) {
            if (!localApiFailed) {
                netLog.info("TailscalePeerResolver: Local API unreachable ({}), will try fallbacks", e.getMessage());
                localApiFailed = true;
            }
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }

        return peers;
    }

    /**
     * Parse Tailscale local API status JSON to extract online peer IPv4 addresses.
     */
    private List<InetAddress> parseLocalApiPeers(final String json) {
        final List<InetAddress> peers = new ArrayList<>();

        // Extract self IPs to exclude
        final Set<String> selfIps = new HashSet<>();
        final int selfStart = json.indexOf("\"Self\"");
        if (selfStart >= 0) {
            final int selfObjStart = json.indexOf("{", selfStart + 6);
            if (selfObjStart >= 0) {
                final int selfObjEnd = findMatchingBrace(json, selfObjStart);
                if (selfObjEnd >= 0) {
                    final String selfObj = json.substring(selfObjStart, selfObjEnd + 1);
                    selfIps.addAll(extractStringArray(selfObj, "TailscaleIPs"));
                }
            }
        }

        // Find the "Peer" map
        final int peerKeyStart = json.indexOf("\"Peer\"");
        if (peerKeyStart < 0) {
            return peers;
        }
        final int colonPos = json.indexOf(":", peerKeyStart + 6);
        if (colonPos < 0) {
            return peers;
        }
        final int peerObjStart = findNonWhitespace(json, colonPos + 1);
        if (peerObjStart < 0 || json.charAt(peerObjStart) != '{') {
            return peers;
        }
        final int peerObjEnd = findMatchingBrace(json, peerObjStart);
        if (peerObjEnd < 0) {
            return peers;
        }

        // Iterate through peer entries
        final String peerMap = json.substring(peerObjStart + 1, peerObjEnd);
        int pos = 0;
        while (pos < peerMap.length()) {
            final int entryStart = peerMap.indexOf("{", pos);
            if (entryStart < 0) {
                break;
            }
            final int entryEnd = findMatchingBrace(peerMap, entryStart);
            if (entryEnd < 0) {
                break;
            }

            final String peerEntry = peerMap.substring(entryStart, entryEnd + 1);

            if (extractBooleanField(peerEntry, "Online")) {
                for (final String ip : extractStringArray(peerEntry, "TailscaleIPs")) {
                    if (!selfIps.contains(ip) && isIPv4(ip)) {
                        try {
                            peers.add(InetAddress.getByName(ip));
                        } catch (final Exception e) {
                            // Skip invalid addresses
                        }
                    }
                }
            }

            pos = entryEnd + 1;
        }

        return peers;
    }

    // ========================================================================
    // Strategy 3: /24 subnet scan fallback
    // ========================================================================

    /**
     * Fallback: generate all /24 addresses for each Tailscale interface.
     * Tailscale IPs are in the CGNAT range 100.64.0.0/10.
     */
    private List<InetAddress> generateSubnetPeers() {
        final List<InetAddress> peers = new ArrayList<>();
        for (final String addr : FServerManager.getAllLanAddresses()) {
            if (!isTailscaleIp(addr)) {
                continue;
            }
            try {
                final byte[] selfIp = InetAddress.getByName(addr).getAddress();
                for (int i = 1; i <= 254; i++) {
                    if (selfIp[3] == (byte) i) {
                        continue;
                    }
                    peers.add(InetAddress.getByAddress(
                            new byte[] { selfIp[0], selfIp[1], selfIp[2], (byte) i }));
                }
                netLog.debug("TailscalePeerResolver: Subnet scan of {}/24 ({} addresses)", addr, peers.size());
            } catch (final Exception e) {
                netLog.error("TailscalePeerResolver: Error generating subnet for {}: {}", addr, e.getMessage());
            }
        }
        return peers;
    }

    // ========================================================================
    // MagicDNS reverse DNS (for debugging)
    // ========================================================================

    /**
     * Try reverse DNS on own Tailscale IP to discover the tailnet name.
     * This uses MagicDNS when the Tailscale VPN is active.
     * Results are logged for debugging.
     */
    private void tryReverseDns() {
        for (final String addr : FServerManager.getAllLanAddresses()) {
            if (!isTailscaleIp(addr)) {
                continue;
            }
            try {
                final String hostname = InetAddress.getByName(addr).getCanonicalHostName();
                if (!hostname.equals(addr)) {
                    netLog.debug("TailscalePeerResolver: Reverse DNS for {} -> {}", addr, hostname);
                } else {
                    netLog.debug("TailscalePeerResolver: Reverse DNS for {} returned raw IP (MagicDNS may not be active)", addr);
                }
            } catch (final Exception e) {
                netLog.debug("TailscalePeerResolver: Reverse DNS failed for {}: {}", addr, e.getMessage());
            }
        }
    }

    // ========================================================================
    // Utility methods
    // ========================================================================

    private static String readResponse(final HttpURLConnection conn) throws Exception {
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

    /**
     * Check if an IP is in the Tailscale CGNAT range (100.64.0.0/10).
     */
    static boolean isTailscaleIp(final String ip) {
        if (ip == null || ip.indexOf(':') >= 0) {
            return false;
        }
        try {
            final byte[] bytes = InetAddress.getByName(ip).getAddress();
            final int first = bytes[0] & 0xFF;
            final int second = bytes[1] & 0xFF;
            return first == 100 && second >= 64 && second <= 127;
        } catch (final Exception e) {
            return false;
        }
    }

    // --- Minimal JSON parsing helpers (no external libraries) ---

    private static List<String> extractStringArray(final String json, final String fieldName) {
        final List<String> values = new ArrayList<>();
        final int fieldStart = json.indexOf("\"" + fieldName + "\"");
        if (fieldStart < 0) {
            return values;
        }
        final int arrayStart = json.indexOf("[", fieldStart);
        if (arrayStart < 0) {
            return values;
        }
        final int arrayEnd = findMatchingBracket(json, arrayStart);
        if (arrayEnd < 0) {
            return values;
        }
        final Matcher matcher = QUOTED_STRING.matcher(json.substring(arrayStart + 1, arrayEnd));
        while (matcher.find()) {
            values.add(matcher.group(1));
        }
        return values;
    }

    private static boolean extractBooleanField(final String json, final String fieldName) {
        final int fieldStart = json.indexOf("\"" + fieldName + "\"");
        if (fieldStart < 0) {
            return false;
        }
        final int colonPos = json.indexOf(":", fieldStart + fieldName.length() + 2);
        if (colonPos < 0) {
            return false;
        }
        final int valueStart = findNonWhitespace(json, colonPos + 1);
        if (valueStart < 0) {
            return false;
        }
        return json.regionMatches(valueStart, "true", 0, 4);
    }

    private static boolean isIPv4(final String ip) {
        return ip.indexOf('.') >= 0 && ip.indexOf(':') < 0;
    }

    private static int findNonWhitespace(final String s, final int from) {
        for (int i = from; i < s.length(); i++) {
            final char c = s.charAt(i);
            if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
                return i;
            }
        }
        return -1;
    }

    private static int findMatchingBrace(final String json, final int openPos) {
        return findMatchingDelimiter(json, openPos, '{', '}');
    }

    private static int findMatchingBracket(final String json, final int openPos) {
        return findMatchingDelimiter(json, openPos, '[', ']');
    }

    private static int findMatchingDelimiter(final String json, final int openPos, final char open, final char close) {
        if (openPos < 0 || openPos >= json.length() || json.charAt(openPos) != open) {
            return -1;
        }
        int depth = 1;
        boolean inString = false;
        for (int i = openPos + 1; i < json.length(); i++) {
            final char c = json.charAt(i);
            if (c == '\\' && inString) {
                i++;
                continue;
            }
            if (c == '"') {
                inString = !inString;
            } else if (!inString) {
                if (c == open) {
                    depth++;
                } else if (c == close) {
                    depth--;
                    if (depth == 0) {
                        return i;
                    }
                }
            }
        }
        return -1;
    }
}
