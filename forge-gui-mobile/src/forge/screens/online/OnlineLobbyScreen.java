package forge.screens.online;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.utils.Align;

import forge.Forge;
import forge.assets.FSkinColor;
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
import forge.gui.interfaces.ILobbyView;
import forge.gui.util.SOptionPane;
import forge.localinstance.properties.ForgeConstants;
import forge.localinstance.properties.ForgeNetPreferences;
import forge.localinstance.skin.FSkinProp;
import forge.model.FModel;
import forge.screens.LoadingOverlay;
import forge.screens.constructed.LobbyScreen;
import forge.screens.match.MatchController;
import forge.screens.online.OnlineMenu.OnlineScreen;
import forge.toolbox.FButton;
import forge.toolbox.FLabel;
import forge.toolbox.FOptionPane;
import forge.util.Utils;
import forge.util.WaitCallback;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class OnlineLobbyScreen extends LobbyScreen implements IOnlineLobby {

    /** Max discovered servers shown at once — keeps the static layout from overflowing. */
    private static final int MAX_DISCOVERED_SHOWN = 4;

    private final FLabel lblTitle;
    private final FLabel lblWarning;
    private final FLabel lblGuideText;
    private final FLabel lblGuideLink;
    private final FButton btnHost;
    private final FButton btnJoin;
    private final FLabel lblDiscoveredHeader;
    private final FLabel lblTailscaleSetup;
    private final List<FButton> discoveredButtons = new ArrayList<>();
    private ServerDiscovery discovery;

    public OnlineLobbyScreen() {
        super(null, OnlineMenu.getMenu(), new OfflineLobby());

        lblTitle = new FLabel.Builder()
                .text("- = *  H E R E   B E   E L D R A Z I  * = -")
                .font(FSkinFont.get(18)).align(Align.center).build();
        add(lblTitle);

        lblWarning = new FLabel.Builder()
                .text(Forge.getLocalizer().getMessage("lblOnlineWarning"))
                .font(FSkinFont.get(14)).align(Align.center).build();
        add(lblWarning);

        lblGuideText = new FLabel.Builder()
                .text(Forge.getLocalizer().getMessage("lblOnlineGuideText"))
                .font(FSkinFont.get(14)).align(Align.center).build();
        add(lblGuideText);

        lblGuideLink = new FLabel.Builder()
                .text(Forge.getLocalizer().getMessage("lblNetworkPlayGuide"))
                .font(FSkinFont.get(14)).align(Align.center)
                .textColor(FSkinColor.get(FSkinColor.Colors.CLR_ACTIVE))
                .command(e -> Gdx.net.openURI(ForgeConstants.NETWORK_PLAY_WIKI_URL)).build();
        add(lblGuideLink);

        btnHost = new FButton(Forge.getLocalizer().getMessage("lblHostGame"));
        btnHost.setCommand(e -> activateHost());
        add(btnHost);

        btnJoin = new FButton(Forge.getLocalizer().getMessage("lblJoinGame"));
        btnJoin.setCommand(e -> activateJoin());
        add(btnJoin);

        lblDiscoveredHeader = new FLabel.Builder()
                .text(Forge.getLocalizer().getMessageorUseDefault("lblDiscoveredServers", "Games found on your network:"))
                .font(FSkinFont.get(14)).align(Align.center).build();
        lblDiscoveredHeader.setVisible(false);
        add(lblDiscoveredHeader);

        lblTailscaleSetup = new FLabel.Builder()
                .text(Forge.getLocalizer().getMessageorUseDefault("lblTailscaleSetup", "Tailscale Setup"))
                .font(FSkinFont.get(14)).align(Align.center)
                .textColor(FSkinColor.get(FSkinColor.Colors.CLR_ACTIVE))
                .command(e -> activateTailscaleSetup()).build();
        add(lblTailscaleSetup);
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
        // closeConn is invoked from the netty event-loop thread on disconnect; screen-stack
        // navigation must happen on the EDT/render thread. If the host died mid-game, run the
        // same teardown a normal game end performs (MatchController.afterGameEnd: navigation
        // plus releasing the ~100-140MB match graph/textures — it treats a null HostedMatch as
        // match-over, i.e. exactly the network-client case); a bare back() would leave the dead
        // game pinned until the next match.
        FThreads.invokeInEdtNowOrLater(() -> {
            if (MatchController.instance.getGameView() != null) {
                MatchController.instance.afterGameEnd();
            } else {
                Forge.back();
            }
        });
        if (msg.length() > 0) {
            FThreads.invokeInBackgroundThread(() -> {
                final boolean callBackAlwaysTrue = SOptionPane.showOptionDialog(msg, Forge.getLocalizer().getMessage("lblError"), FSkinProp.ICO_WARNING, List.of(Forge.getLocalizer().getMessage("lblOK")), 1) == 0;
                if (callBackAlwaysTrue) { //to activate online menu popup when player press play online
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
        if (getGameLobby() == null) {
            startDiscovery();
            revalidate();
        } else {
            super.onActivate();
        }
    }

    @Override
    public void onSwitchAway(Consumer<Boolean> canSwitchCallback) {
        stopDiscovery();
        super.onSwitchAway(canSwitchCallback);
    }

    /**
     * Starts listening for LAN/Tailscale discovery broadcasts. Guarded so a
     * discovery failure can never break the normal host/join flows.
     */
    private void startDiscovery() {
        if (discovery != null) {
            return;
        }
        try {
            final ServerDiscovery d = new ServerDiscovery();
            // Listener fires on the discovery thread — hop to the EDT for UI updates
            d.setListener(servers -> FThreads.invokeInEdtLater(() -> updateDiscoveredServers(servers)));
            discovery = d;
            d.start();
        } catch (final Throwable t) {
            discovery = null;
            System.err.println("ServerDiscovery failed to start: " + t.getMessage());
        }
    }

    private void stopDiscovery() {
        final ServerDiscovery d = discovery;
        discovery = null;
        if (d != null) {
            try {
                d.stop();
            } catch (final Throwable t) {
                System.err.println("ServerDiscovery failed to stop: " + t.getMessage());
            }
        }
        updateDiscoveredServers(List.of());
    }

    /** Rebuilds the discovered-server buttons. Must be called on the EDT. */
    private void updateDiscoveredServers(final List<ServerDiscovery.DiscoveredServer> servers) {
        for (final FButton btn : discoveredButtons) {
            remove(btn);
        }
        discoveredButtons.clear();
        if (discovery != null && getGameLobby() == null) {
            for (final ServerDiscovery.DiscoveredServer server : servers) {
                if (discoveredButtons.size() >= MAX_DISCOVERED_SHOWN) {
                    break;
                }
                final FButton btn = new FButton(server.toString());
                btn.setCommand(e -> joinDiscoveredServer(server));
                discoveredButtons.add(btn);
                add(btn);
            }
        }
        lblDiscoveredHeader.setVisible(!discoveredButtons.isEmpty());
        revalidate();
    }

    @Override
    protected void doLayoutAboveBtnStart(float startY, float width, float height) {
        if (getGameLobby() == null) {
            btnStart.setVisible(false);
            setLobbyControlsVisible(false);

            float padding = Utils.scale(10);
            float y = startY + height * 0.15f;

            float labelHeight = lblTitle.getAutoSizeBounds().height + padding;
            lblTitle.setBounds(padding, y, width - 2 * padding, labelHeight);
            lblTitle.setVisible(true);
            y += labelHeight + padding * 2;

            labelHeight = lblWarning.getAutoSizeBounds().height + padding;
            lblWarning.setBounds(padding, y, width - 2 * padding, labelHeight);
            lblWarning.setVisible(true);
            y += labelHeight + padding;

            labelHeight = lblGuideText.getAutoSizeBounds().height + padding;
            lblGuideText.setBounds(padding, y, width - 2 * padding, labelHeight);
            lblGuideText.setVisible(true);
            y += labelHeight;

            labelHeight = lblGuideLink.getAutoSizeBounds().height + padding;
            lblGuideLink.setBounds(padding, y, width - 2 * padding, labelHeight);
            lblGuideLink.setVisible(true);
            y += labelHeight + padding * 4;

            float buttonGap = padding * 2;
            float buttonWidth = width * 0.35f;
            float totalButtonWidth = buttonWidth * 2 + buttonGap;
            float buttonX = (width - totalButtonWidth) / 2;
            float buttonHeight = Utils.AVG_FINGER_HEIGHT;
            btnHost.setBounds(buttonX, y, buttonWidth, buttonHeight);
            btnHost.setVisible(true);
            btnJoin.setBounds(buttonX + buttonWidth + buttonGap, y, buttonWidth, buttonHeight);
            btnJoin.setVisible(true);
            y += buttonHeight + padding * 2;

            // Discovered servers (if any)
            if (!discoveredButtons.isEmpty()) {
                labelHeight = lblDiscoveredHeader.getAutoSizeBounds().height + padding;
                lblDiscoveredHeader.setBounds(padding, y, width - 2 * padding, labelHeight);
                lblDiscoveredHeader.setVisible(true);
                y += labelHeight;
                float serverButtonWidth = width * 0.7f;
                float serverButtonX = (width - serverButtonWidth) / 2;
                for (final FButton btn : discoveredButtons) {
                    btn.setBounds(serverButtonX, y, serverButtonWidth, buttonHeight);
                    btn.setVisible(true);
                    y += buttonHeight + padding;
                }
                y += padding;
            } else {
                lblDiscoveredHeader.setVisible(false);
            }

            // Tailscale setup link (credential for cross-network discovery)
            labelHeight = lblTailscaleSetup.getAutoSizeBounds().height + padding;
            lblTailscaleSetup.setBounds(padding, y, width - 2 * padding, labelHeight);
            lblTailscaleSetup.setVisible(true);
        } else {
            lblTitle.setVisible(false);
            lblWarning.setVisible(false);
            lblGuideText.setVisible(false);
            lblGuideLink.setVisible(false);
            btnHost.setVisible(false);
            btnJoin.setVisible(false);
            lblDiscoveredHeader.setVisible(false);
            lblTailscaleSetup.setVisible(false);
            for (final FButton btn : discoveredButtons) {
                btn.setVisible(false);
            }
            setLobbyControlsVisible(true);
            btnStart.setVisible(true);

            super.doLayoutAboveBtnStart(startY, width, height);
        }
    }

    private void activateHost() {
        stopDiscovery();
        setGameLobby(getLobby());
        revalidate();
        NetConnectUtil.ensurePlayerName();
        final String caption = Forge.getLocalizer().getMessage("lblStartingServer");
        LoadingOverlay.show(caption, true, () -> {
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

    private void activateJoin() {
        stopDiscovery();
        setGameLobby(getLobby());
        revalidate();
        FThreads.invokeInBackgroundThread(() -> {
            final String url = NetConnectUtil.getJoinServerUrl();
            FThreads.invokeInEdtLater(() -> {
                if (url == null) {
                    closeConn("");
                    return;
                }
                final String caption = Forge.getLocalizer().getMessage("lblConnectingToServer");
                LoadingOverlay.show(caption, true, () -> {
                    final ChatMessage[] result = new ChatMessage[1];
                    final IOnlineChatInterface chatInterface = (IOnlineChatInterface) OnlineScreen.Chat.getScreen();
                    result[0] = NetConnectUtil.join(url, OnlineLobbyScreen.this, chatInterface);
                    String message = result[0].getMessage();
                    if (ForgeConstants.CLOSE_CONN_COMMAND.equals(message)) {
                        closeConn(Forge.getLocalizer().getMessage("UnableConnectToServer", url));
                        return;
                    } else if (message != null && message.startsWith(ForgeConstants.CONN_ERROR_PREFIX)) {
                        String errorDetail = message.substring(ForgeConstants.CONN_ERROR_PREFIX.length());
                        closeConn(errorDetail);
                        return;
                    } else if (ForgeConstants.INVALID_HOST_COMMAND.equals(message)) {
                        closeConn(Forge.getLocalizer().getMessage("lblDetectedInvalidHostAddress", url));
                        return;
                    }
                    chatInterface.addMessage(result[0]);
                    OnlineScreen.Lobby.update();
                });
            });
        });
    }

    /**
     * Joins a server found via LAN/Tailscale discovery, trying its addresses
     * in priority order (Tailscale CGNAT first, then LAN, then the rest) until
     * one connects.
     */
    private void joinDiscoveredServer(final ServerDiscovery.DiscoveredServer server) {
        stopDiscovery();
        setGameLobby(getLobby());
        revalidate();
        FThreads.invokeInBackgroundThread(() -> {
            NetConnectUtil.ensurePlayerName();
            final List<String> urls = NetConnectUtil.getPrioritizedServerUrls(server);
            FThreads.invokeInEdtLater(() -> {
                final String caption = Forge.getLocalizer().getMessage("lblConnectingToServer");
                LoadingOverlay.show(caption, true, () -> {
                    final IOnlineChatInterface chatInterface = (IOnlineChatInterface) OnlineScreen.Chat.getScreen();
                    ChatMessage result = null;
                    String message = null;
                    String lastUrl = server.address() + ":" + server.gamePort();
                    for (final String url : urls) {
                        lastUrl = url;
                        result = NetConnectUtil.join(url, OnlineLobbyScreen.this, chatInterface);
                        message = result.getMessage();
                        if (!isJoinFailure(message)) {
                            break; // connected successfully
                        }
                        System.out.println("Connection to " + url + " failed, trying next address...");
                    }
                    if (result == null || ForgeConstants.CLOSE_CONN_COMMAND.equals(message)) {
                        closeConn(Forge.getLocalizer().getMessage("UnableConnectToServer", lastUrl));
                        return;
                    } else if (message != null && message.startsWith(ForgeConstants.CONN_ERROR_PREFIX)) {
                        closeConn(message.substring(ForgeConstants.CONN_ERROR_PREFIX.length()));
                        return;
                    } else if (ForgeConstants.INVALID_HOST_COMMAND.equals(message)) {
                        closeConn(Forge.getLocalizer().getMessage("lblDetectedInvalidHostAddress", lastUrl));
                        return;
                    }
                    chatInterface.addMessage(result);
                    OnlineScreen.Lobby.update();
                });
            });
        });
    }

    private static boolean isJoinFailure(final String message) {
        return ForgeConstants.CLOSE_CONN_COMMAND.equals(message)
                || ForgeConstants.INVALID_HOST_COMMAND.equals(message)
                || (message != null && message.startsWith(ForgeConstants.CONN_ERROR_PREFIX));
    }

    /**
     * Prompts for the Tailscale OAuth client credential used to auto-discover games across
     * networks when hosting. Runs on a background thread because the input dialogs block.
     */
    private void activateTailscaleSetup() {
        FThreads.invokeInBackgroundThread(() -> {
            final String curId = FModel.getNetPreferences().getPref(ForgeNetPreferences.FNetPref.TAILSCALE_OAUTH_CLIENT_ID);
            final String curSecret = FModel.getNetPreferences().getPref(ForgeNetPreferences.FNetPref.TAILSCALE_OAUTH_CLIENT_SECRET);
            final boolean configured = curSecret != null && !curSecret.isEmpty();
            final String title = Forge.getLocalizer().getMessageorUseDefault("lblTailscaleSetup", "Tailscale Setup");

            String idPrompt = Forge.getLocalizer().getMessageorUseDefault("lblTailscaleClientIdPrompt",
                    "Cross-network game discovery uses a Tailscale OAuth client.\n\n"
                    + "Create one in the Tailscale admin console under Settings > OAuth clients "
                    + "with the 'devices' READ scope, then enter its Client ID here.\n\n"
                    + "(Legacy tskey-api access tokens also work: leave the Client ID blank "
                    + "and paste the token as the secret. Note they expire within 90 days.)");
            if (configured) {
                idPrompt += "\n\nA credential is already saved. Leave the NEXT prompt blank to clear it.";
            }

            final String newId = SOptionPane.showInputDialog(idPrompt, title, null, curId == null ? "" : curId);
            if (newId == null) {
                return; // cancelled
            }

            final String secretPrompt = Forge.getLocalizer().getMessageorUseDefault("lblTailscaleClientSecretPrompt",
                    "Enter the OAuth Client Secret (or a legacy tskey-api token).\n"
                    + (configured ? "Leave blank to CLEAR the saved credential." : "Leave blank to cancel."));
            // Masked input (bullets) — the secret is long-lived and grants tailnet device
            // enumeration, so don't render it on screen while it's typed.
            final String newSecret = new WaitCallback<String>() {
                @Override
                public void run() {
                    FOptionPane.showInputDialog(secretPrompt, title, "", null, this, false, true);
                }
            }.invokeAndWait();
            if (newSecret == null) {
                return; // cancelled
            }

            if (newSecret.trim().isEmpty()) {
                if (configured) {
                    FModel.getNetPreferences().setPref(ForgeNetPreferences.FNetPref.TAILSCALE_OAUTH_CLIENT_ID, "");
                    FModel.getNetPreferences().setPref(ForgeNetPreferences.FNetPref.TAILSCALE_OAUTH_CLIENT_SECRET, "");
                    FModel.getNetPreferences().save();
                    SOptionPane.showMessageDialog(Forge.getLocalizer().getMessageorUseDefault(
                            "lblTailscaleCredentialCleared", "Tailscale credential cleared."));
                }
                return;
            }

            FModel.getNetPreferences().setPref(ForgeNetPreferences.FNetPref.TAILSCALE_OAUTH_CLIENT_ID, newId.trim());
            FModel.getNetPreferences().setPref(ForgeNetPreferences.FNetPref.TAILSCALE_OAUTH_CLIENT_SECRET, newSecret.trim());
            FModel.getNetPreferences().save();
            SOptionPane.showMessageDialog(Forge.getLocalizer().getMessageorUseDefault(
                    "lblTailscaleCredentialSaved",
                    "Tailscale credential saved. Guests on your tailnet will auto-discover games you host."));
        });
    }

}
