package forge.game.event;

import forge.game.card.Card;

import java.util.Arrays;
import java.util.Collection;

public class GameEventCardRegenerated implements GameEvent {
    private final Collection<Card> cards;

    public GameEventCardRegenerated(Collection<Card> cards) {
        this.cards = cards;
    }

    public Collection<Card> cards() {
        return cards;
    }


    public GameEventCardRegenerated(Card affected) {
        this(Arrays.asList(affected));
    }

    @Override
    public <T> T visit(IGameEventVisitor<T> visitor) {
        return visitor.visit(this);
    }
    @Override
    public int hashCode() {
        int result = 17;
        result = 31 * result + (cards != null ? cards.hashCode() : 0);
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        GameEventCardRegenerated that = (GameEventCardRegenerated) obj;
        return java.util.Objects.equals(cards, that.cards);
    }

    @Override
    public String toString() {
        return "GameEventCardRegenerated[cards=" + cards + "]";
    }
}
