package forge.screens.online;

import com.badlogic.gdx.Input.Keys;
import com.google.common.collect.ImmutableList;

import forge.Forge;
import forge.assets.FSkinFont;
import forge.gamemodes.match.GameLobby;
import forge.gamemodes.net.ChatMessage;
import forge.gamemodes.net.IOnlineChatInterface;
import forge.gamemodes.net.IOnlineLobby;
import forge.gamemodes.net.NetConnectUtil;
import forge.gamemodes.net.OfflineLobby;
import forge.gamemodes.net.client.FGameClient;
import forge.gamemodes.net.client.ServerDiscovery;
import forge.gamemodes.net.server.FServerManager;
import forge.gui.FThreads;
import forge.gui.GuiBase;
import forge.gui.interfaces.ILobbyView;
import forge.gui.util.SOptionPane;
import forge.localinstance.properties.ForgeConstants;
import forge.localinstance.properties.ForgeNetPreferences;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.localinstance.skin.FSkinProp;
import forge.model.FModel;
import forge.player.GamePlayerUtil;
import forge.screens.LoadingOverlay;
import forge.screens.constructed.LobbyScreen;
import forge.screens.online.OnlineMenu.OnlineScreen;
import forge.toolbox.FButton;
import forge.toolbox.FDialog;
import forge.toolbox.FOptionPane;
import forge.toolbox.FScrollPane;
import forge.toolbox.FTextArea;
import forge.util.Utils;
import forge.util.WaitCallback;
import forge.util.function.Consumer;

import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;

public class OnlineLobbyScreen extends LobbyScreen implements IOnlineLobby {
    public OnlineLobbyScreen() {
        super(null, OnlineMenu.getMenu(), new OfflineLobby());
    }

    private static GameLobby gameLobby;

    public static GameLobby getGameLobby() {
        return gameLobby;
    }

    public static void clearGameLobby() {
        gameLobby = null;
    }

    public static void setGameLobby(GameLobby gameLobby) {
        OnlineLobbyScreen.gameLobby = gameLobby;
    }

    private static FGameClient fGameClient;

    public static FGameClient getfGameClient() {
        return fGameClient;
    }

    public static void closeClient() {
        getfGameClient().close();
        fGameClient = null;
    }

    @Override
    public void closeConn(String msg) {
        clearGameLobby();
        // Must navigate on EDT — closeConn may be called from Netty thread
        FThreads.invokeInEdtLater(() -> Forge.back());
        if (msg.length() > 0) {
            FThreads.invokeInBackgroundThread(() -> {
                final boolean callBackAlwaysTrue = SOptionPane.showOptionDialog(msg, Forge.getLocalizer().getMessage("lblError"), FSkinProp.ICO_WARNING, ImmutableList.of(Forge.getLocalizer().getMessage("lblOK")), 1) == 0;
                if (callBackAlwaysTrue) { //to activate online menu popup when player press play online
                    GuiBase.setInterrupted(false);

                    if(FServerManager.getInstance() != null)
                        FServerManager.getInstance().stopServer();
                    if(getfGameClient() != null)
                        closeClient();
                }
            });
        }
    }

    @Override
    public ILobbyView setLobby(GameLobby lobby0) {
        initLobby(lobby0);
        return this;
    }

    @Override
    public void setClient(FGameClient client) {
        fGameClient = client;
    }

    @Override
    public void onActivate() {
        if (GuiBase.isInterrupted()) {
            GuiBase.setInterrupted(false);
            return;
        }
        if (getGameLobby() == null) {
            setGameLobby(getLobby());

            // Start LAN server discovery scan on background thread
            FThreads.invokeInBackgroundThread(() -> {
                // Ensure player name is set before proceeding (uses blocking dialog)
                if (StringUtils.isBlank(FModel.getPreferences().getPref(FPref.PLAYER_NAME))) {
                    GamePlayerUtil.setPlayerName();
                }

                final ServerDiscovery discovery = new ServerDiscovery();
                discovery.start();

                // Initial 3-second scan to find LAN/Tailscale servers
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException e) {
                    // ignore
                }

                // Loop: show options, re-scan if user picks "Scan Again"
                showConnectionOptions(discovery);
            });
        }
    }

    /**
     * Shows a dialog with vertical buttons, blocking the calling thread.
     * Returns the selected option index, or -1 if cancelled/back.
     */
    private int showVerticalOptionDialog(final String message, final String title, final List<String> options) {
        return new WaitCallback<Integer>() {
            @Override
            public void run() {
                VerticalOptionDialog.show(message, title, options, this);
            }
        }.invokeAndWait();
    }

    /**
     * Called from background thread — blocking dialog calls require non-EDT.
     * Loops until the user picks an action (discovery stays running for continuous scanning).
     */
    private void showConnectionOptions(final ServerDiscovery discovery) {
        while (true) {
            final List<ServerDiscovery.DiscoveredServer> discovered = discovery.getServers();

            // Build option list: discovered servers + actions
            final List<String> options = new ArrayList<String>();
            for (ServerDiscovery.DiscoveredServer server : discovered) {
                options.add(server.toString());
            }
            final int hostIndex = options.size();
            options.add(Forge.getLocalizer().getMessageorUseDefault("lblHostGame", "Host Game"));
            final int joinIndex = options.size();
            options.add(Forge.getLocalizer().getMessageorUseDefault("lblJoinByIP", "Join by IP"));
            final int tailscaleIndex = options.size();
            options.add(Forge.getLocalizer().getMessageorUseDefault("lblTailscaleSetup", "Tailscale Setup"));
            final int scanIndex = options.size();
            options.add(Forge.getLocalizer().getMessageorUseDefault("lblScanAgain", "Scan Again"));

            String title = Forge.getLocalizer().getMessageorUseDefault("lblOnlineMultiplayer", "Online Multiplayer");
            String message;
            if (discovered.isEmpty()) {
                message = Forge.getLocalizer().getMessageorUseDefault("lblNoGamesFound", "No games found on local network.");
            } else {
                message = Forge.getLocalizer().getMessageorUseDefault("lblSearchingForGames",
                        "Found games on local network. Select a game to join, or choose another option.");
            }

            final int choice = showVerticalOptionDialog(message, title, options);

            if (choice == scanIndex) {
                // Re-scan: wait 3 seconds for fresh results, then loop back
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException e) {
                    // ignore
                }
                continue;
            }

            // User picked an action — stop discovery and proceed
            discovery.stop();

            if (choice == hostIndex) {
                FThreads.invokeInEdtLater(() -> startHostFlow());
            } else if (choice == joinIndex) {
                startJoinByIpFlow();
            } else if (choice == tailscaleIndex) {
                startTailscaleSetupFlow();
            } else if (choice >= 0 && choice < discovered.size()) {
                final ServerDiscovery.DiscoveredServer server = discovered.get(choice);
                // Build ordered list of addresses to try, prioritized:
                // 1. Tailscale CGNAT (100.64-127.x.x) — most likely to work cross-network
                // 2. UDP source IP (it delivered the packet, so it's routable)
                // 3. Private LAN IPs (192.168.x, 10.x, 172.16-31.x)
                // 4. Everything else (public IPv6, etc.)
                final java.util.List<String> allIps = new java.util.ArrayList<String>();
                allIps.add(server.address);
                for (String ip : server.allHostIps) {
                    if (!allIps.contains(ip)) {
                        allIps.add(ip);
                    }
                }
                // Sort by priority: Tailscale first, then private IPv4, then rest
                final java.util.List<String> tailscale = new java.util.ArrayList<String>();
                final java.util.List<String> privateIps = new java.util.ArrayList<String>();
                final java.util.List<String> other = new java.util.ArrayList<String>();
                for (String ip : allIps) {
                    if (isTailscaleAddress(ip)) {
                        tailscale.add(ip);
                    } else if (isPrivateAddress(ip)) {
                        privateIps.add(ip);
                    } else {
                        other.add(ip);
                    }
                }
                final java.util.List<String> allUrls = new java.util.ArrayList<String>();
                for (String ip : tailscale) {
                    allUrls.add(formatAddressPort(ip, server.gamePort));
                }
                for (String ip : privateIps) {
                    allUrls.add(formatAddressPort(ip, server.gamePort));
                }
                for (String ip : other) {
                    allUrls.add(formatAddressPort(ip, server.gamePort));
                }
                FThreads.invokeInEdtLater(() -> startJoinFlow(allUrls));
            } else {
                FThreads.invokeInEdtLater(() -> closeConn(""));
            }
            break;
        }
    }

    /**
     * A dialog that displays options as vertical buttons in a scrollable list.
     * Supports any number of options (unlike FOptionPane which is limited to 3).
     * Uses VPrompt bar for title and a "Back" button.
     */
    private static class VerticalOptionDialog extends FDialog {
        private final FTextArea msgArea;
        private final FScrollPane buttonScroller;
        private final List<FButton> optionButtons;
        private final Consumer<Integer> callback;

        static void show(String message, String title, List<String> options, Consumer<Integer> callback) {
            VerticalOptionDialog dlg = new VerticalOptionDialog(message, title, options, callback);
            dlg.show();
        }

        private VerticalOptionDialog(String message, String title, List<String> options, Consumer<Integer> callback0) {
            super(title, 1); // 1 VPrompt button for "Back"
            this.callback = callback0;
            this.optionButtons = new ArrayList<FButton>();

            // Set up message area
            if (message != null && !message.isEmpty()) {
                msgArea = add(new FTextArea(true, message));
                msgArea.setFont(FSkinFont.get(12));
            } else {
                msgArea = null;
            }

            // Create scrollable button list
            buttonScroller = add(new FScrollPane() {
                @Override
                protected ScrollBounds layoutAndGetScrollBounds(float visibleWidth, float visibleHeight) {
                    float btnHeight = FSkinFont.get(14).getCapHeight() * 3.5f;
                    float pad = Utils.scale(5);
                    float y = 0;
                    for (int i = 0; i < optionButtons.size(); i++) {
                        optionButtons.get(i).setBounds(0, y, visibleWidth, btnHeight);
                        y += btnHeight + pad;
                    }
                    return new ScrollBounds(visibleWidth, y > 0 ? y - pad : 0);
                }
            });

            for (int i = 0; i < options.size(); i++) {
                final int index = i;
                FButton btn = new FButton(options.get(i));
                btn.setCommand(e -> selectOption(index));
                optionButtons.add(btn);
                buttonScroller.add(btn);
            }

            // Initialize "Back" button in VPrompt bar
            initButton(0, Forge.getLocalizer().getMessageorUseDefault("lblBack", "Back"),
                    e -> selectOption(-1));
        }

        private void selectOption(int index) {
            hide();
            if (callback != null) {
                callback.accept(index);
            }
        }

        @Override
        protected float layoutAndGetHeight(float width, float maxHeight) {
            float padding = FOptionPane.PADDING;
            float y = padding;

            // Layout message
            if (msgArea != null) {
                float promptWidth = width - 2 * padding;
                float prefH = msgArea.getPreferredHeight(promptWidth);
                msgArea.setBounds(padding, y, promptWidth, prefH);
                y += prefH + padding;
            }

            // Layout button scroller
            float btnHeight = FSkinFont.get(14).getCapHeight() * 3.5f;
            float btnPad = Utils.scale(5);
            float totalBtnHeight = optionButtons.size() * (btnHeight + btnPad) - btnPad;
            float availableHeight = maxHeight - y - padding;
            float scrollerHeight = Math.min(totalBtnHeight, availableHeight);

            buttonScroller.setBounds(padding, y, width - 2 * padding, scrollerHeight);
            y += scrollerHeight + padding;

            return y;
        }

        @Override
        public boolean keyDown(int keyCode) {
            switch (keyCode) {
                case Keys.ESCAPE:
                case Keys.BACK:
                case Keys.BUTTON_B:
                    if (Forge.endKeyInput()) { return true; }
                    selectOption(-1);
                    return true;
                default:
                    return super.keyDown(keyCode);
            }
        }
    }

    private void startHostFlow() {
        LoadingOverlay.show(Forge.getLocalizer().getMessage("lblStartingServer"), true, () -> {
            final ChatMessage[] result = new ChatMessage[1];
            final IOnlineChatInterface chatInterface = (IOnlineChatInterface) OnlineScreen.Chat.getScreen();
            FThreads.invokeInBackgroundThread(() -> {
                result[0] = NetConnectUtil.host(OnlineLobbyScreen.this, chatInterface);
                chatInterface.addMessage(result[0]);
                NetConnectUtil.copyHostedServerUrl();
            });
            OnlineScreen.Lobby.update();
        });
    }

    /** Called from background thread. */
    private void startJoinByIpFlow() {
        // SOptionPane.showInputDialog blocks (uses WaitCallback), must be on background thread
        final String url = SOptionPane.showInputDialog(
                Forge.getLocalizer().getMessage("lblOnlineMultiplayerDest"),
                Forge.getLocalizer().getMessageorUseDefault("lblConnectToServer", "Connect to Server"));
        if (url == null || url.isEmpty()) {
            FThreads.invokeInEdtLater(() -> closeConn(""));
            return;
        }
        FThreads.invokeInEdtLater(() -> startJoinFlow(url, null));
    }

    /** Called from background thread. */
    private void startTailscaleSetupFlow() {
        String currentKey = FModel.getNetPreferences().getPref(
                ForgeNetPreferences.FNetPref.TAILSCALE_API_KEY);
        String prompt = Forge.getLocalizer().getMessageorUseDefault("lblTailscaleApiKeyPrompt",
                "Enter your Tailscale API key for cross-network discovery.");
        if (currentKey != null && !currentKey.isEmpty()) {
            // Show masked version of existing key
            String masked = currentKey.substring(0, Math.min(10, currentKey.length())) + "...";
            prompt += "\n\nCurrent key: " + masked + "\n(Leave blank to clear)";
        }

        final String newKey = SOptionPane.showInputDialog(prompt,
                Forge.getLocalizer().getMessageorUseDefault("lblTailscaleSetup", "Tailscale Setup"));
        if (newKey == null) {
            // User cancelled — go back to connection options
            FThreads.invokeInEdtLater(() -> {
                clearGameLobby();
                Forge.back();
            });
            return;
        }

        if (newKey.isEmpty() && currentKey != null && !currentKey.isEmpty()) {
            // Clear existing key
            FModel.getNetPreferences().setPref(ForgeNetPreferences.FNetPref.TAILSCALE_API_KEY, "");
            FModel.getNetPreferences().save();
            SOptionPane.showMessageDialog(
                    Forge.getLocalizer().getMessageorUseDefault("lblTailscaleApiKeyCleared",
                            "Tailscale API key cleared."));
        } else if (!newKey.isEmpty()) {
            FModel.getNetPreferences().setPref(ForgeNetPreferences.FNetPref.TAILSCALE_API_KEY, newKey.trim());
            FModel.getNetPreferences().save();
            SOptionPane.showMessageDialog(
                    Forge.getLocalizer().getMessageorUseDefault("lblTailscaleApiKeySaved",
                            "Tailscale API key saved. It will be used when hosting games."));
        }

        // Go back to connection options
        FThreads.invokeInEdtLater(() -> {
            clearGameLobby();
            Forge.back();
        });
    }

    /**
     * Formats an address and port for URL parsing. Brackets IPv6 addresses
     * so the URI parser can distinguish address from port.
     */
    private static String formatAddressPort(String address, int port) {
        if (address.contains(":")) {
            // IPv6 — needs brackets
            return "[" + address + "]:" + port;
        }
        return address + ":" + port;
    }

    private void startJoinFlow(final java.util.List<String> urls) {
        LoadingOverlay.show(Forge.getLocalizer().getMessage("lblConnectingToServer"), true, () -> {
            final IOnlineChatInterface chatInterface = (IOnlineChatInterface) OnlineScreen.Chat.getScreen();
            ChatMessage result = null;
            String lastUrl = null;
            for (String url : urls) {
                lastUrl = url;
                System.out.println("Trying connection to " + url + " (" + urls.indexOf(url) + "/" + urls.size() + ")");
                result = NetConnectUtil.join(url, OnlineLobbyScreen.this, chatInterface);
                if (result.getMessage() != ForgeConstants.CLOSE_CONN_COMMAND) {
                    break; // Connected successfully
                }
                System.out.println("Connection to " + url + " failed, trying next address...");
            }
            if (result == null || result.getMessage() == ForgeConstants.CLOSE_CONN_COMMAND) {
                closeConn(Forge.getLocalizer().getMessage("UnableConnectToServer", lastUrl));
                return;
            } else if (result.getMessage() == ForgeConstants.INVALID_HOST_COMMAND) {
                closeConn(Forge.getLocalizer().getMessage("lblDetectedInvalidHostAddress", lastUrl));
                return;
            }
            chatInterface.addMessage(result);
            OnlineScreen.Lobby.update();
        });
    }

    /** Returns true if the IP is in Tailscale's CGNAT range (100.64.0.0/10). */
    private static boolean isTailscaleAddress(String ip) {
        if (ip.contains(":")) {
            return false; // IPv6
        }
        try {
            String[] octets = ip.split("\\.");
            if (octets.length != 4) {
                return false;
            }
            int first = Integer.parseInt(octets[0]);
            int second = Integer.parseInt(octets[1]);
            // 100.64.0.0/10 = 100.64.0.0 - 100.127.255.255
            return first == 100 && second >= 64 && second <= 127;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** Returns true if the IP is a private/LAN address (192.168.x, 10.x, 172.16-31.x). */
    private static boolean isPrivateAddress(String ip) {
        if (ip.contains(":")) {
            return false; // IPv6
        }
        try {
            String[] octets = ip.split("\\.");
            if (octets.length != 4) {
                return false;
            }
            int first = Integer.parseInt(octets[0]);
            int second = Integer.parseInt(octets[1]);
            return first == 10
                    || (first == 172 && second >= 16 && second <= 31)
                    || (first == 192 && second == 168);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** Overload for manual IP entry (single URL, no fallbacks). */
    private void startJoinFlow(final String url, final String fallbackUrl) {
        java.util.List<String> urls = new java.util.ArrayList<String>();
        urls.add(url);
        if (fallbackUrl != null) {
            urls.add(fallbackUrl);
        }
        startJoinFlow(urls);
    }
}
