package forge.game.event;

import forge.game.player.Player;

public class GameEventCardModeChosen implements GameEvent {
    private final Player player;
    private final String cardName;
    private final String mode;
    private final boolean log;
    private final boolean random;

    public GameEventCardModeChosen(Player player, String cardName, String mode, boolean log, boolean random) {
        this.player = player;
        this.cardName = cardName;
        this.mode = mode;
        this.log = log;
        this.random = random;
    }

    public Player player() {
        return player;
    }

    public String cardName() {
        return cardName;
    }

    public String mode() {
        return mode;
    }

    public boolean log() {
        return log;
    }

    public boolean random() {
        return random;
    }



    @Override
    public <T> T visit(IGameEventVisitor<T> visitor) {
        return visitor.visit(this);
    }
    @Override
    public int hashCode() {
        int result = 17;
        result = 31 * result + (player != null ? player.hashCode() : 0);
        result = 31 * result + (cardName != null ? cardName.hashCode() : 0);
        result = 31 * result + (mode != null ? mode.hashCode() : 0);
        result = 31 * result + (log ? 1 : 0);
        result = 31 * result + (random ? 1 : 0);
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        GameEventCardModeChosen that = (GameEventCardModeChosen) obj;
        return java.util.Objects.equals(player, that.player) &&
               java.util.Objects.equals(cardName, that.cardName) &&
               java.util.Objects.equals(mode, that.mode) &&
               log == that.log &&
               random == that.random;
    }

    @Override
    public String toString() {
        return "GameEventCardModeChosen[player=" + player + ", cardName=" + cardName + ", mode=" + mode + ", log=" + log + ", random=" + random + "]";
    }
}
