package forge.gamemodes.net.server;

import forge.LobbyPlayer;
import forge.ai.GameState;
import forge.deck.CardPool;
import forge.game.GameEntityView;
import forge.game.GameView;
import forge.game.card.CardView;
import forge.game.phase.PhaseType;
import forge.game.player.DelayedReveal;
import forge.game.player.IHasIcon;
import forge.game.player.PlayerView;
import forge.game.spellability.SpellAbilityView;
import forge.game.zone.ZoneType;
import forge.gamemodes.match.AbstractGuiGame;
import forge.gamemodes.net.GameProtocolSender;
import forge.gamemodes.net.NetStubs;
import forge.gamemodes.net.ProtocolMethod;
import forge.item.PaperCard;
import forge.localinstance.skin.FSkinProp;
import forge.player.PlayerZoneUpdate;
import forge.player.PlayerZoneUpdates;
import forge.trackable.TrackableCollection;
import forge.util.FSerializableFunction;
import forge.util.ITriggerEvent;

import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

public class NetGuiGame extends AbstractGuiGame {

    private final GameProtocolSender sender;
    private final HashMap<String, Boolean> phaseStopCache = new HashMap<String, Boolean>();
    private boolean gameViewDirty = false;
    private int gameViewSkipCount = 0;

    // Coalescing buffers: accumulate updateCards/updateZones calls and flush
    // once before user-facing operations. Since flushGameView() always sends
    // full state first, the coalesced sends only need IDs as refresh signals.
    private final HashSet<Integer> pendingCardIds = new HashSet<Integer>();
    private final HashMap<Integer, EnumSet<ZoneType>> pendingZonePlayerIds = new HashMap<Integer, EnumSet<ZoneType>>();

    public NetGuiGame(final IToClient client) {
        this.sender = new GameProtocolSender(client);
    }

    public void setCachedPhaseStop(final PlayerView playerTurn, final PhaseType phase, final boolean stop) {
        String key = playerTurn.getId() + ":" + phase.name();
        phaseStopCache.put(key, stop);
    }

    private void send(final ProtocolMethod method, final Object... args) {
        if (method == ProtocolMethod.setGameView || method == ProtocolMethod.openView) {
            sender.send(method, args);
        } else {
            sender.send(method, NetStubs.stripArgs(args));
        }
    }

    private <T> T sendAndWait(final ProtocolMethod method, final Object... args) {
        // Always flush all pending state before blocking for client response
        flushPendingUpdates();
        if (method == ProtocolMethod.setGameView || method == ProtocolMethod.openView) {
            return sender.sendAndWait(method, args);
        }
        return sender.sendAndWait(method, NetStubs.stripArgs(args));
    }

    /**
     * Marks the game view as needing to be sent. The actual send is deferred
     * until a user-facing operation or sendAndWait requires the client to have
     * current state. This coalesces 5-10 redundant full GameView serializations
     * per game action down to 1-2.
     */
    public void updateGameView() {
        if (gameViewDirty) {
            gameViewSkipCount++;
        }
        gameViewDirty = true;
    }

    /**
     * Sends the full GameView to the client if it has been marked dirty.
     * Called before user-facing operations that need the client to display
     * current game state (prompts, selectables, dialogs).
     */
    private void flushGameView() {
        if (gameViewDirty) {
            if (gameViewSkipCount > 0) {
                System.err.println("[ERR] NET GAMEVIEW: flush (skipped " + gameViewSkipCount + " redundant sends)");
            }
            send(ProtocolMethod.setGameView, getGameView());
            gameViewDirty = false;
            gameViewSkipCount = 0;
        }
    }

    /**
     * Flushes all pending state: full GameView first, then coalesced
     * updateCards/updateZones with ID-only stubs. Since setGameView carries
     * the complete game state, the client only needs card/zone IDs to know
     * which UI elements to refresh. This replaces direct flushGameView()
     * calls at user-facing operation boundaries.
     */
    private void flushPendingUpdates() {
        flushGameView();

        if (!pendingCardIds.isEmpty()) {
            TrackableCollection<CardView> stubs = new TrackableCollection<CardView>();
            for (Integer cardId : pendingCardIds) {
                stubs.add(new CardView(cardId));
            }
            System.err.println("[ERR] NET COALESCE: updateCards " + pendingCardIds.size() + " cards");
            pendingCardIds.clear();
            sender.send(ProtocolMethod.updateCards, new Object[] { stubs });
        }

        if (!pendingZonePlayerIds.isEmpty()) {
            PlayerZoneUpdates stubs = new PlayerZoneUpdates();
            for (Map.Entry<Integer, EnumSet<ZoneType>> entry : pendingZonePlayerIds.entrySet()) {
                PlayerView stubPlayer = new PlayerView(entry.getKey(), null);
                stubs.add(new PlayerZoneUpdate(stubPlayer, entry.getValue()));
            }
            System.err.println("[ERR] NET COALESCE: updateZones " + pendingZonePlayerIds.size() + " players");
            pendingZonePlayerIds.clear();
            sender.send(ProtocolMethod.updateZones, new Object[] { stubs });
        }
    }

    @Override
    public void setGameView(final GameView gameView) {
        super.setGameView(gameView);
        // Always send immediately during initialization
        send(ProtocolMethod.setGameView, getGameView());
        gameViewDirty = false;
    }

    @Override
    public void openView(final TrackableCollection<PlayerView> myPlayers) {
        // Warm up the network connection before sending game data.
        // Over Tailscale, the first packets go through a DERP relay (~100-500ms RTT).
        // After a few seconds, Tailscale establishes a direct WireGuard connection
        // (~5-50ms RTT). We ping until RTT drops below the threshold or timeout.
        waitForDirectConnection();

        send(ProtocolMethod.openView, myPlayers);
        // Always send immediately during initialization
        send(ProtocolMethod.setGameView, getGameView());
        gameViewDirty = false;

        // Populate phase stop cache from client for ALL players (not just
        // myPlayers). The client has phase indicators for both their own turn
        // and the opponent's turn. Without the opponent's stops, the host
        // skips all opponent phases and never gives the client priority.
        GameView gv = getGameView();
        if (gv != null && gv.getPlayers() != null) {
            for (final PlayerView player : gv.getPlayers()) {
                HashMap<String, Boolean> stops = sendAndWait(ProtocolMethod.getAllPhaseStops, player);
                if (stops != null) {
                    phaseStopCache.putAll(stops);
                }
            }
        }
    }

    /**
     * Sends ping/pong round-trips until the RTT drops below 100ms
     * (indicating a direct Tailscale WireGuard connection), or 10 seconds
     * elapse. With message size optimizations, the game is playable even
     * over DERP relay, so we don't wait longer than 10 seconds.
     */
    private void waitForDirectConnection() {
        final long DIRECT_THRESHOLD_MS = 100;
        final long TIMEOUT_MS = 10000;
        final long RETRY_DELAY_MS = 2000;

        long startTime = System.currentTimeMillis();
        int attempt = 0;

        while (System.currentTimeMillis() - startTime < TIMEOUT_MS) {
            attempt++;
            long pingStart = System.currentTimeMillis();
            try {
                sendAndWait(ProtocolMethod.ping);
            } catch (Exception e) {
                System.err.println("[ERR] NET WARMUP: ping failed: " + e.getMessage());
                break;
            }
            long rtt = System.currentTimeMillis() - pingStart;
            long elapsed = System.currentTimeMillis() - startTime;
            System.err.println("[ERR] NET WARMUP: ping #" + attempt + " RTT=" + rtt + "ms"
                    + (rtt < DIRECT_THRESHOLD_MS ? " (direct)" : " (relayed)")
                    + " elapsed=" + (elapsed / 1000) + "s");

            if (rtt < DIRECT_THRESHOLD_MS) {
                System.err.println("[ERR] NET WARMUP: direct connection established after "
                        + elapsed + "ms (" + attempt + " pings)");
                return;
            }

            try {
                Thread.sleep(RETRY_DELAY_MS);
            } catch (InterruptedException e) {
                break;
            }
        }

        System.err.println("[ERR] NET WARMUP: timed out after " + attempt + " pings ("
                + ((System.currentTimeMillis() - startTime) / 1000) + "s), proceeding with relayed connection");
    }

    @Override
    public void afterGameEnd() {
        send(ProtocolMethod.afterGameEnd);
    }

    @Override
    public void showCombat() {
        send(ProtocolMethod.showCombat);
    }

    @Override
    public void showPromptMessage(final PlayerView playerView, final String message) {
        flushPendingUpdates();
        send(ProtocolMethod.showPromptMessage, playerView, message);
    }

    @Override
    public void showCardPromptMessage(final PlayerView playerView, final String message, final CardView card) {
        flushPendingUpdates();
        send(ProtocolMethod.showCardPromptMessage, playerView, message, card);
    }

    @Override
    public void updateButtons(final PlayerView owner, final String label1, final String label2, final boolean enable1, final boolean enable2, final boolean focus1) {
        send(ProtocolMethod.updateButtons, owner, label1, label2, enable1, enable2, focus1);
    }

    @Override
    public void flashIncorrectAction() {
        send(ProtocolMethod.flashIncorrectAction);
    }

    @Override
    public void alertUser() { send(ProtocolMethod.alertUser); }

    @Override
    public void updatePhase(boolean saveState) {
        // Phase/turn data is set directly on the GameView by the game engine.
        // The client reads getGameView().getPhase() when processing updatePhase,
        // so it needs the GameView to have current data. Flush all pending state
        // (GameView + buffered cards/zones) so cards reflect current tapped state etc.
        gameViewDirty = true;
        flushPendingUpdates();
        send(ProtocolMethod.updatePhase, saveState);
    }

    @Override
    public void updateTurn(final PlayerView player) {
        // Don't flush here — updatePhase/showPromptMessage follows immediately
        // and will flush all accumulated state in one shot.
        send(ProtocolMethod.updateTurn, player);
    }

    @Override
    public void updatePlayerControl() {
        updateGameView();
        send(ProtocolMethod.updatePlayerControl);
    }

    @Override
    public void enableOverlay() {
        send(ProtocolMethod.enableOverlay);
    }

    @Override
    public void disableOverlay() {
        send(ProtocolMethod.disableOverlay);
    }

    @Override
    public void finishGame() {
        send(ProtocolMethod.finishGame);
    }

    @Override
    public void showManaPool(final PlayerView player) {
        send(ProtocolMethod.showManaPool, player);
    }

    @Override
    public void hideManaPool(final PlayerView player) {
        send(ProtocolMethod.hideManaPool, player);
    }

    @Override
    public void updateStack() {
        // Client reads stack data from the GameView. Flush all pending state
        // (GameView + buffered cards/zones) so everything is current.
        gameViewDirty = true;
        flushPendingUpdates();
        send(ProtocolMethod.updateStack);
    }

    @Override
    public void updateZones(final Iterable<PlayerZoneUpdate> zonesToUpdate) {
        // Buffer zone updates — flushPendingUpdates() will send a single
        // coalesced message with ID-only stubs after setGameView.
        // Mark GameView dirty so flushPendingUpdates() sends full state
        // before the ID-only stubs (client needs current data to resolve them).
        gameViewDirty = true;
        for (PlayerZoneUpdate pzu : zonesToUpdate) {
            int playerId = pzu.getPlayer().getId();
            EnumSet<ZoneType> existing = pendingZonePlayerIds.get(playerId);
            if (existing == null) {
                existing = EnumSet.noneOf(ZoneType.class);
                pendingZonePlayerIds.put(playerId, existing);
            }
            existing.addAll(pzu.getZones());
        }
    }

    @Override
    public Iterable<PlayerZoneUpdate> tempShowZones(final PlayerView controller, final Iterable<PlayerZoneUpdate> zonesToUpdate) {
        // sendAndWait already calls flushPendingUpdates()
        return sendAndWait(ProtocolMethod.tempShowZones, controller, zonesToUpdate);
    }

    @Override
    public void hideZones(final PlayerView controller, final Iterable<PlayerZoneUpdate> zonesToUpdate) {
        updateGameView();
        send(ProtocolMethod.hideZones, controller, zonesToUpdate);
    }

    @Override
    public void updateCards(final Iterable<CardView> cards) {
        // Buffer card IDs — flushPendingUpdates() will send a single
        // coalesced message with ID-only stubs after setGameView.
        // Mark GameView dirty so flushPendingUpdates() sends full state
        // before the ID-only stubs (client needs current data to resolve them).
        gameViewDirty = true;
        for (CardView card : cards) {
            pendingCardIds.add(card.getId());
        }
    }

    @Override
    public void updateManaPool(final Iterable<PlayerView> manaPoolUpdate) {
        // Client reads mana data from the resolved PlayerView, so it needs
        // the GameView to have current data. Flush all pending state
        // (GameView + buffered cards/zones) so cards reflect current tapped state etc.
        gameViewDirty = true;
        flushPendingUpdates();
        send(ProtocolMethod.updateManaPool, manaPoolUpdate);
    }

    @Override
    public void updateLives(final Iterable<PlayerView> livesUpdate) {
        // Client reads life data from the resolved PlayerView, so it needs
        // the GameView to have current data. Flush all pending state
        // (GameView + buffered cards/zones) so everything is current.
        gameViewDirty = true;
        flushPendingUpdates();
        send(ProtocolMethod.updateLives, livesUpdate);
    }

    @Override
    public void updateShards(Iterable<PlayerView> shardsUpdate) {
        //mobile adventure local game only..
    }

    @Override
    public void setPanelSelection(final CardView hostCard) {
        updateGameView();
        send(ProtocolMethod.setPanelSelection, hostCard);
    }

    @Override
    public void refreshField() {
        updateGameView();
        send(ProtocolMethod.refreshField);
    }

    @Override
    public GameState getGamestate() {
        return null;
    }

    @Override
    public SpellAbilityView getAbilityToPlay(final CardView hostCard, final List<SpellAbilityView> abilities, final ITriggerEvent triggerEvent) {
        return sendAndWait(ProtocolMethod.getAbilityToPlay, hostCard, abilities, null/*triggerEvent*/); //someplatform don't have mousetriggerevent class or it will not allow them to click/tap
    }

    @Override
    public Map<CardView, Integer> assignCombatDamage(final CardView attacker, final List<CardView> blockers, final int damage, final GameEntityView defender, final boolean overrideOrder, final boolean maySkip) {
        return sendAndWait(ProtocolMethod.assignCombatDamage, attacker, blockers, damage, defender, overrideOrder, maySkip);
    }

    @Override
    public Map<Object, Integer> assignGenericAmount(final CardView effectSource, final Map<Object, Integer> targets, final int amount, final boolean atLeastOne, final String amountLabel) {
        return sendAndWait(ProtocolMethod.assignGenericAmount, effectSource, targets, amount, atLeastOne, amountLabel);
    }

    @Override
    public void message(final String message, final String title) {
        send(ProtocolMethod.message, message, title);
    }

    @Override
    public void showErrorDialog(final String message, final String title) {
        send(ProtocolMethod.showErrorDialog, message, title);
    }

    @Override
    public boolean showConfirmDialog(final String message, final String title, final String yesButtonText, final String noButtonText, final boolean defaultYes) {
        return sendAndWait(ProtocolMethod.showConfirmDialog, message, title, yesButtonText, noButtonText, defaultYes);
    }

    @Override
    public int showOptionDialog(final String message, final String title, final FSkinProp icon, final List<String> options, final int defaultOption) {
        return sendAndWait(ProtocolMethod.showOptionDialog, message, title, icon, options, defaultOption);
    }

    @Override
    public String showInputDialog(final String message, final String title, final FSkinProp icon, final String initialInput, final List<String> inputOptions, final boolean isNumeric) {
        return sendAndWait(ProtocolMethod.showInputDialog, message, title, icon, initialInput, inputOptions, isNumeric);
    }

    @Override
    public boolean confirm(final CardView c, final String question, final boolean defaultIsYes, final List<String> options) {
        return sendAndWait(ProtocolMethod.confirm, c, question, defaultIsYes, options);
    }

    @Override
    public <T> List<T> getChoices(final String message, final int min, final int max, final List<T> choices, final List<T> selected, final FSerializableFunction<T, String> display) {
        return sendAndWait(ProtocolMethod.getChoices, message, min, max, choices, selected, display);
    }

    @Override
    public <T> List<T> order(final String title, final String top, final int remainingObjectsMin, final int remainingObjectsMax, final List<T> sourceChoices, final List<T> destChoices, final CardView referenceCard, final boolean sideboardingMode) {
        return sendAndWait(ProtocolMethod.order, title, top, remainingObjectsMin, remainingObjectsMax, sourceChoices, destChoices, referenceCard, sideboardingMode);
    }

    @Override
    public List<PaperCard> sideboard(final CardPool sideboard, final CardPool main, final String message) {
        return sendAndWait(ProtocolMethod.sideboard, sideboard, main, message);
    }

    @Override
    public GameEntityView chooseSingleEntityForEffect(final String title, final List<? extends GameEntityView> optionList, final DelayedReveal delayedReveal, final boolean isOptional) {
        return sendAndWait(ProtocolMethod.chooseSingleEntityForEffect, title, optionList, delayedReveal, isOptional);
    }

    @Override
    public List<GameEntityView> chooseEntitiesForEffect(final String title, final List<? extends GameEntityView> optionList, final int min, final int max, final DelayedReveal delayedReveal) {
        return sendAndWait(ProtocolMethod.chooseEntitiesForEffect, title, optionList, min, max, delayedReveal);
    }

    @Override
    public List<CardView> manipulateCardList(final String title, final Iterable<CardView> cards, final Iterable<CardView> manipulable, final boolean toTop, final boolean toBottom, final boolean toAnywhere) {
        return sendAndWait(ProtocolMethod.manipulateCardList, title, cards, manipulable, toTop, toBottom, toAnywhere);
    }

    @Override
    public void setCard(final CardView card) {
        send(ProtocolMethod.setCard, card);
    }

    @Override
    public void setUsedToPay(final CardView card, final boolean value) {
        super.setUsedToPay(card, value);
        send(ProtocolMethod.setUsedToPay, card, value);
    }

    @Override
    public void setSelectables(final Iterable<CardView> cards) {
        send(ProtocolMethod.setSelectables, cards);
    }

    @Override
    public void clearSelectables() {
        send(ProtocolMethod.clearSelectables);
    }

    @Override
    public void setPlayerAvatar(final LobbyPlayer player, final IHasIcon ihi) {
        // TODO Auto-generated method stub
    }

    @Override
    public PlayerZoneUpdates openZones(PlayerView controller, final Collection<ZoneType> zones, final Map<PlayerView, Object> players, boolean backupLastZones) {
        // sendAndWait already calls flushPendingUpdates()
        return sendAndWait(ProtocolMethod.openZones, controller, zones, players, backupLastZones);
    }

    @Override
    public void restoreOldZones(PlayerView playerView, PlayerZoneUpdates playerZoneUpdates) {
        send(ProtocolMethod.restoreOldZones, playerView, playerZoneUpdates);
    }

    @Override
    public boolean isUiSetToSkipPhase(final PlayerView playerTurn, final PhaseType phase) {
        String key = playerTurn.getId() + ":" + phase.name();
        Boolean cached = phaseStopCache.get(key);
        // If cached: stopAtPhase=true means "stop" (don't skip), so return !cached
        // If not cached: default to skip (return true)
        return cached == null || !cached;
    }

    @Override
    protected void updateCurrentPlayer(final PlayerView player) {
        // TODO Auto-generated method stub
    }

}
