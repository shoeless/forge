package forge.game.event;

import forge.card.CardStateName;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.util.Lang;

public class GameEventDoorChanged implements GameEvent {
    private final Player activatingPlayer;
    private final Card card;
    private final CardStateName state;
    private final boolean unlock;

    public GameEventDoorChanged(Player activatingPlayer, Card card, CardStateName state, boolean unlock) {
        this.activatingPlayer = activatingPlayer;
        this.card = card;
        this.state = state;
        this.unlock = unlock;
    }

    public Player activatingPlayer() {
        return activatingPlayer;
    }

    public Card card() {
        return card;
    }

    public CardStateName state() {
        return state;
    }

    public boolean unlock() {
        return unlock;
    }



    @Override
    public <T> T visit(IGameEventVisitor<T> visitor) {
        return visitor.visit(this);
    }

    @Override
    public String toString() {
        String doorName = card.getState(state).getTranslatedName();

        StringBuilder sb = new StringBuilder();
        sb.append(activatingPlayer);
        sb.append(" ");
        sb.append(unlock ? "unlocks" : "locks");
        sb.append(" ");
        sb.append(Lang.getInstance().getPossessedObject(doorName, "Door"));
        return sb.toString();
    }
    @Override
    public int hashCode() {
        int result = 17;
        result = 31 * result + (activatingPlayer != null ? activatingPlayer.hashCode() : 0);
        result = 31 * result + (card != null ? card.hashCode() : 0);
        result = 31 * result + (state != null ? state.hashCode() : 0);
        result = 31 * result + (unlock ? 1 : 0);
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        GameEventDoorChanged that = (GameEventDoorChanged) obj;
        return java.util.Objects.equals(activatingPlayer, that.activatingPlayer) &&
               java.util.Objects.equals(card, that.card) &&
               java.util.Objects.equals(state, that.state) &&
               unlock == that.unlock;
    }
}
