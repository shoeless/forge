package forge.game.event;

import forge.game.player.Player;

// This special event denotes loss of mana due to phase end
public class GameEventManaBurn implements GameEvent {
    private final Player player;
    private final boolean causedLifeLoss;
    private final int amount;

    public GameEventManaBurn(Player player, boolean causedLifeLoss, int amount) {
        this.player = player;
        this.causedLifeLoss = causedLifeLoss;
        this.amount = amount;
    }

    public Player player() {
        return player;
    }

    public boolean causedLifeLoss() {
        return causedLifeLoss;
    }

    public int amount() {
        return amount;
    }



    @Override
    public <T> T visit(IGameEventVisitor<T> visitor) {
        return visitor.visit(this);
    }
    @Override
    public int hashCode() {
        int result = 17;
        result = 31 * result + (player != null ? player.hashCode() : 0);
        result = 31 * result + (causedLifeLoss ? 1 : 0);
        result = 31 * result + amount;
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        GameEventManaBurn that = (GameEventManaBurn) obj;
        return java.util.Objects.equals(player, that.player) &&
               causedLifeLoss == that.causedLifeLoss &&
               amount == that.amount;
    }

    @Override
    public String toString() {
        return "GameEventManaBurn[player=" + player + ", causedLifeLoss=" + causedLifeLoss + ", amount=" + amount + "]";
    }
}
