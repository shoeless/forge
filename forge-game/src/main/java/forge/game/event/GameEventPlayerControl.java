package forge.game.event;

import forge.LobbyPlayer;
import forge.game.player.Player;
import forge.game.player.PlayerController;

public class GameEventPlayerControl implements GameEvent {
    private final Player player;
    private final LobbyPlayer oldLobbyPlayer;
    private final PlayerController oldController;
    private final LobbyPlayer newLobbyPlayer;
    private final PlayerController newController;

    public GameEventPlayerControl(Player player, LobbyPlayer oldLobbyPlayer, PlayerController oldController, LobbyPlayer newLobbyPlayer, PlayerController newController) {
        this.player = player;
        this.oldLobbyPlayer = oldLobbyPlayer;
        this.oldController = oldController;
        this.newLobbyPlayer = newLobbyPlayer;
        this.newController = newController;
    }

    public Player player() {
        return player;
    }

    public LobbyPlayer oldLobbyPlayer() {
        return oldLobbyPlayer;
    }

    public PlayerController oldController() {
        return oldController;
    }

    public LobbyPlayer newLobbyPlayer() {
        return newLobbyPlayer;
    }

    public PlayerController newController() {
        return newController;
    }



    @Override
    public <T> T visit(final IGameEventVisitor<T> visitor) {
        return visitor.visit(this);
    }

    /* (non-Javadoc)
     * @see java.lang.Object#toString()
     */
    @Override
    public String toString() {
        return "" + player + " controlled by " + player.getControllingPlayer();
    }
    @Override
    public int hashCode() {
        int result = 17;
        result = 31 * result + (player != null ? player.hashCode() : 0);
        result = 31 * result + (oldLobbyPlayer != null ? oldLobbyPlayer.hashCode() : 0);
        result = 31 * result + (oldController != null ? oldController.hashCode() : 0);
        result = 31 * result + (newLobbyPlayer != null ? newLobbyPlayer.hashCode() : 0);
        result = 31 * result + (newController != null ? newController.hashCode() : 0);
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        GameEventPlayerControl that = (GameEventPlayerControl) obj;
        return java.util.Objects.equals(player, that.player) &&
               java.util.Objects.equals(oldLobbyPlayer, that.oldLobbyPlayer) &&
               java.util.Objects.equals(oldController, that.oldController) &&
               java.util.Objects.equals(newLobbyPlayer, that.newLobbyPlayer) &&
               java.util.Objects.equals(newController, that.newController);
    }
}
