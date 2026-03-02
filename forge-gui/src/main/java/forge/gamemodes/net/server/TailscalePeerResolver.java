package forge.gamemodes.net.server;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
 *       — requires an API key configured in server preferences. Returns all
 *       devices on the tailnet with their Tailscale IPs. Works from iOS
 *       since it's a standard HTTPS request.</li>
 *   <li><b>Tailscale local API</b> ({@code http://100.100.100.100/localapi/v0/status})
 *       — returns precise list of online peers. Works on macOS/Linux, blocked
 *       on iOS.</li>
 *   <li><b>/24 subnet scan</b> — sends to all 254 addresses in the host's
 *       Tailscale /24 subnet. Best-effort fallback for same-subnet peers.</li>
 * </ol>
 *
 * <p>Results are cached for 60 seconds.</p>
 */
public class TailscalePeerResolver {
    private static final String TAILSCALE_CLOUD_API_URL =
            "https://api.tailscale.com/api/v2/tailnet/-/devices?fields=default";
    private static final String TAILSCALE_LOCAL_API_URL =
            "http://100.100.100.100/localapi/v0/status";
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 5000;
    private static final long CACHE_DURATION_MS = 60000;

    private static final Pattern QUOTED_STRING = Pattern.compile("\"([^\"]+)\"");

    private String apiKey;
    private List<InetAddress> cachedPeers = new ArrayList<InetAddress>();
    private long cacheTimestamp = 0;
    private boolean localApiFailed = false;
    private boolean cloudApiLoggedSuccess = false;
    private boolean cloudApiLoggedFailure = false;
    private boolean reverseDnsLogged = false;

    /**
     * Set the Tailscale API key for cloud-based peer discovery.
     * Generate one at https://login.tailscale.com/admin/settings/keys
     */
    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    /**
     * Returns Tailscale peer IPv4 addresses to send discovery packets to.
     * Cached for 60 seconds. Thread-safe for a single caller (the broadcast thread).
     */
    public List<InetAddress> getOnlinePeers() {
        long now = System.currentTimeMillis();
        if (now - cacheTimestamp < CACHE_DURATION_MS) {
            return cachedPeers;
        }

        // Try reverse DNS on own Tailscale IP for debugging (once)
        if (!reverseDnsLogged) {
            tryReverseDns();
            reverseDnsLogged = true;
        }

        List<InetAddress> peers = new ArrayList<InetAddress>();

        // Strategy 1: Tailscale Cloud API (works on iOS, needs API key)
        if (apiKey != null && !apiKey.trim().isEmpty()) {
            peers = queryCloudApi();
        }

        // Strategy 2: Tailscale local API (blocked on iOS but works on desktop)
        if (peers.isEmpty() && (apiKey == null || apiKey.trim().isEmpty())) {
            peers = queryLocalApi();
        }

        // Strategy 3: /24 subnet scan fallback
        if (peers.isEmpty()) {
            peers = generateSubnetPeers();
        }

        cachedPeers = peers;
        cacheTimestamp = now;
        return cachedPeers;
    }

    // ========================================================================
    // Strategy 1: Tailscale Cloud REST API
    // ========================================================================

    /**
     * Query the Tailscale Cloud API for all devices on the tailnet.
     * Returns Tailscale IPv4 addresses of all devices except self.
     *
     * <p>API endpoint: GET https://api.tailscale.com/api/v2/tailnet/-/devices</p>
     * <p>Auth: Bearer token (API key from admin console)</p>
     */
    private List<InetAddress> queryCloudApi() {
        List<InetAddress> peers = new ArrayList<InetAddress>();
        HttpURLConnection conn = null;
        try {
            URL url = new URL(TAILSCALE_CLOUD_API_URL);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Authorization", "Bearer " + apiKey.trim());

            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                if (!cloudApiLoggedFailure) {
                    System.out.println("TailscalePeerResolver: Cloud API returned HTTP "
                            + responseCode);
                    cloudApiLoggedFailure = true;
                }
                return peers;
            }

            StringBuilder sb = new StringBuilder();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), "UTF-8"));
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            reader.close();

            peers = parseCloudDevices(sb.toString());

            if (!cloudApiLoggedSuccess) {
                System.out.println("TailscalePeerResolver: Cloud API found "
                        + peers.size() + " peer(s)");
                cloudApiLoggedSuccess = true;
                cloudApiLoggedFailure = false;
            }

        } catch (Exception e) {
            if (!cloudApiLoggedFailure) {
                System.out.println("TailscalePeerResolver: Cloud API error: "
                        + e.getMessage());
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
     *       "addresses": ["100.67.114.108", "fd7a:..."],
     *       "hostname": "chris-sholleys-ipad",
     *       "name": "chris-sholleys-ipad.tailnet.ts.net",
     *       ...
     *     }
     *   ]
     * }
     * </pre>
     */
    private List<InetAddress> parseCloudDevices(String json) {
        List<InetAddress> peers = new ArrayList<InetAddress>();

        // Get our own Tailscale IPs to exclude
        Set<String> selfIps = new HashSet<String>();
        List<String> lanAddresses = FServerManager.getAllLanAddresses();
        for (String addr : lanAddresses) {
            if (isTailscaleIp(addr)) {
                selfIps.add(addr);
            }
        }

        // Find the "devices" array
        int devicesStart = json.indexOf("\"devices\"");
        if (devicesStart < 0) {
            return peers;
        }
        int arrayStart = json.indexOf("[", devicesStart);
        if (arrayStart < 0) {
            return peers;
        }
        int arrayEnd = findMatchingBracket(json, arrayStart);
        if (arrayEnd < 0) {
            return peers;
        }

        // Iterate through device objects
        String devicesArray = json.substring(arrayStart + 1, arrayEnd);
        int pos = 0;
        while (pos < devicesArray.length()) {
            int objStart = devicesArray.indexOf("{", pos);
            if (objStart < 0) {
                break;
            }
            int objEnd = findMatchingBrace(devicesArray, objStart);
            if (objEnd < 0) {
                break;
            }

            String deviceObj = devicesArray.substring(objStart, objEnd + 1);

            // Extract addresses
            List<String> addresses = extractStringArray(deviceObj, "addresses");
            String hostname = extractStringField(deviceObj, "hostname");

            for (String ip : addresses) {
                if (isIPv4(ip) && isTailscaleIp(ip) && !selfIps.contains(ip)) {
                    try {
                        peers.add(InetAddress.getByName(ip));
                        if (hostname != null) {
                            System.out.println("TailscalePeerResolver: Found peer '"
                                    + hostname + "' at " + ip);
                        }
                    } catch (Exception e) {
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
        List<InetAddress> peers = new ArrayList<InetAddress>();
        HttpURLConnection conn = null;
        try {
            URL url = new URL(TAILSCALE_LOCAL_API_URL);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(3000);
            conn.setRequestMethod("GET");

            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                if (!localApiFailed) {
                    System.out.println("TailscalePeerResolver: Local API returned HTTP "
                            + responseCode + ", will try fallbacks");
                    localApiFailed = true;
                }
                return peers;
            }

            StringBuilder sb = new StringBuilder();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), "UTF-8"));
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            reader.close();

            peers = parseLocalApiPeers(sb.toString());

            if (localApiFailed) {
                System.out.println("TailscalePeerResolver: Local API now reachable");
                localApiFailed = false;
            }
            if (!peers.isEmpty()) {
                System.out.println("TailscalePeerResolver: Local API found "
                        + peers.size() + " online peer(s)");
            }

        } catch (Exception e) {
            if (!localApiFailed) {
                System.out.println("TailscalePeerResolver: Local API unreachable ("
                        + e.getMessage() + "), will try fallbacks");
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
    private List<InetAddress> parseLocalApiPeers(String json) {
        List<InetAddress> peers = new ArrayList<InetAddress>();

        // Extract self IPs to exclude
        Set<String> selfIps = new HashSet<String>();
        int selfStart = json.indexOf("\"Self\"");
        if (selfStart >= 0) {
            int selfObjStart = json.indexOf("{", selfStart + 6);
            if (selfObjStart >= 0) {
                int selfObjEnd = findMatchingBrace(json, selfObjStart);
                if (selfObjEnd >= 0) {
                    String selfObj = json.substring(selfObjStart, selfObjEnd + 1);
                    selfIps.addAll(extractStringArray(selfObj, "TailscaleIPs"));
                }
            }
        }

        // Find the "Peer" map
        int peerKeyStart = json.indexOf("\"Peer\"");
        if (peerKeyStart < 0) {
            return peers;
        }
        int colonPos = json.indexOf(":", peerKeyStart + 6);
        if (colonPos < 0) {
            return peers;
        }
        int peerObjStart = findNonWhitespace(json, colonPos + 1);
        if (peerObjStart < 0 || json.charAt(peerObjStart) != '{') {
            return peers;
        }
        int peerObjEnd = findMatchingBrace(json, peerObjStart);
        if (peerObjEnd < 0) {
            return peers;
        }

        // Iterate through peer entries
        String peerMap = json.substring(peerObjStart + 1, peerObjEnd);
        int pos = 0;
        while (pos < peerMap.length()) {
            int entryStart = peerMap.indexOf("{", pos);
            if (entryStart < 0) {
                break;
            }
            int entryEnd = findMatchingBrace(peerMap, entryStart);
            if (entryEnd < 0) {
                break;
            }

            String peerEntry = peerMap.substring(entryStart, entryEnd + 1);

            if (extractBooleanField(peerEntry, "Online")) {
                List<String> ips = extractStringArray(peerEntry, "TailscaleIPs");
                for (String ip : ips) {
                    if (!selfIps.contains(ip) && isIPv4(ip)) {
                        try {
                            peers.add(InetAddress.getByName(ip));
                        } catch (Exception e) {
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
        List<InetAddress> peers = new ArrayList<InetAddress>();
        List<String> lanAddresses = FServerManager.getAllLanAddresses();

        for (String addr : lanAddresses) {
            if (!isTailscaleIp(addr)) {
                continue;
            }
            try {
                byte[] selfIp = InetAddress.getByName(addr).getAddress();
                for (int i = 1; i <= 254; i++) {
                    if (selfIp[3] == (byte) i) {
                        continue;
                    }
                    byte[] peerBytes = new byte[] { selfIp[0], selfIp[1], selfIp[2], (byte) i };
                    peers.add(InetAddress.getByAddress(peerBytes));
                }
                System.out.println("TailscalePeerResolver: Subnet scan of "
                        + addr + "/24 (" + peers.size() + " addresses)");
            } catch (Exception e) {
                System.err.println("TailscalePeerResolver: Error generating subnet for "
                        + addr + ": " + e.getMessage());
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
        List<String> lanAddresses = FServerManager.getAllLanAddresses();
        for (String addr : lanAddresses) {
            if (!isTailscaleIp(addr)) {
                continue;
            }
            try {
                InetAddress inetAddr = InetAddress.getByName(addr);
                String hostname = inetAddr.getCanonicalHostName();
                if (!hostname.equals(addr)) {
                    System.out.println("TailscalePeerResolver: Reverse DNS for "
                            + addr + " -> " + hostname);
                } else {
                    System.out.println("TailscalePeerResolver: Reverse DNS for "
                            + addr + " returned raw IP (MagicDNS may not be active)");
                }
            } catch (Exception e) {
                System.out.println("TailscalePeerResolver: Reverse DNS failed for "
                        + addr + ": " + e.getMessage());
            }
        }
    }

    // ========================================================================
    // Utility methods
    // ========================================================================

    /**
     * Check if an IP is in the Tailscale CGNAT range (100.64.0.0/10).
     */
    static boolean isTailscaleIp(String ip) {
        if (ip == null || ip.indexOf(':') >= 0) {
            return false;
        }
        try {
            byte[] bytes = InetAddress.getByName(ip).getAddress();
            int first = bytes[0] & 0xFF;
            int second = bytes[1] & 0xFF;
            return first == 100 && second >= 64 && second <= 127;
        } catch (Exception e) {
            return false;
        }
    }

    // --- JSON parsing helpers (Java 7 compatible, no external libraries) ---

    private List<String> extractStringArray(String json, String fieldName) {
        List<String> values = new ArrayList<String>();
        int fieldStart = json.indexOf("\"" + fieldName + "\"");
        if (fieldStart < 0) {
            return values;
        }
        int arrayStart = json.indexOf("[", fieldStart);
        if (arrayStart < 0) {
            return values;
        }
        int arrayEnd = findMatchingBracket(json, arrayStart);
        if (arrayEnd < 0) {
            return values;
        }
        String arrayContent = json.substring(arrayStart + 1, arrayEnd);
        Matcher matcher = QUOTED_STRING.matcher(arrayContent);
        while (matcher.find()) {
            values.add(matcher.group(1));
        }
        return values;
    }

    private String extractStringField(String json, String fieldName) {
        int fieldStart = json.indexOf("\"" + fieldName + "\"");
        if (fieldStart < 0) {
            return null;
        }
        int colonPos = json.indexOf(":", fieldStart + fieldName.length() + 2);
        if (colonPos < 0) {
            return null;
        }
        int valueStart = findNonWhitespace(json, colonPos + 1);
        if (valueStart < 0 || json.charAt(valueStart) != '"') {
            return null;
        }
        int valueEnd = json.indexOf("\"", valueStart + 1);
        if (valueEnd < 0) {
            return null;
        }
        return json.substring(valueStart + 1, valueEnd);
    }

    private boolean extractBooleanField(String json, String fieldName) {
        int fieldStart = json.indexOf("\"" + fieldName + "\"");
        if (fieldStart < 0) {
            return false;
        }
        int colonPos = json.indexOf(":", fieldStart + fieldName.length() + 2);
        if (colonPos < 0) {
            return false;
        }
        int valueStart = findNonWhitespace(json, colonPos + 1);
        if (valueStart < 0) {
            return false;
        }
        return json.regionMatches(valueStart, "true", 0, 4);
    }

    private static boolean isIPv4(String ip) {
        return ip.indexOf('.') >= 0 && ip.indexOf(':') < 0;
    }

    private static int findNonWhitespace(String s, int from) {
        for (int i = from; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
                return i;
            }
        }
        return -1;
    }

    private static int findMatchingBrace(String json, int openPos) {
        if (openPos < 0 || openPos >= json.length() || json.charAt(openPos) != '{') {
            return -1;
        }
        int depth = 1;
        boolean inString = false;
        for (int i = openPos + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && inString) {
                i++;
                continue;
            }
            if (c == '"') {
                inString = !inString;
            } else if (!inString) {
                if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    depth--;
                    if (depth == 0) {
                        return i;
                    }
                }
            }
        }
        return -1;
    }

    private static int findMatchingBracket(String json, int openPos) {
        if (openPos < 0 || openPos >= json.length() || json.charAt(openPos) != '[') {
            return -1;
        }
        int depth = 1;
        boolean inString = false;
        for (int i = openPos + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && inString) {
                i++;
                continue;
            }
            if (c == '"') {
                inString = !inString;
            } else if (!inString) {
                if (c == '[') {
                    depth++;
                } else if (c == ']') {
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
