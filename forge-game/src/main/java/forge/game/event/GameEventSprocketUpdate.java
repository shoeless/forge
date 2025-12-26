package forge.game.event;

import forge.game.card.Card;

public class GameEventSprocketUpdate implements GameEvent {
    private final Card contraption;
    private final int oldSprocket;
    private final int sprocket;

    public GameEventSprocketUpdate(Card contraption, int oldSprocket, int sprocket) {
        this.contraption = contraption;
        this.oldSprocket = oldSprocket;
        this.sprocket = sprocket;
    }

    public Card contraption() {
        return contraption;
    }

    public int oldSprocket() {
        return oldSprocket;
    }

    public int sprocket() {
        return sprocket;
    }



    @Override
    public <T> T visit(IGameEventVisitor<T> visitor) {
        return visitor.visit(this);
    }
    @Override
    public int hashCode() {
        int result = 17;
        result = 31 * result + (contraption != null ? contraption.hashCode() : 0);
        result = 31 * result + oldSprocket;
        result = 31 * result + sprocket;
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        GameEventSprocketUpdate that = (GameEventSprocketUpdate) obj;
        return java.util.Objects.equals(contraption, that.contraption) &&
               oldSprocket == that.oldSprocket &&
               sprocket == that.sprocket;
    }

    @Override
    public String toString() {
        return "GameEventSprocketUpdate[contraption=" + contraption + ", oldSprocket=" + oldSprocket + ", sprocket=" + sprocket + "]";
    }
}
