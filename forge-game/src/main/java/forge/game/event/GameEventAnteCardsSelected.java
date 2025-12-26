package forge.game.event;

import com.google.common.collect.Multimap;

import forge.game.card.Card;
import forge.game.player.Player;

public class GameEventAnteCardsSelected implements GameEvent {
    private final Multimap<Player, Card> cards;

    public GameEventAnteCardsSelected(Multimap<Player, Card> cards) {
        this.cards = cards;
    }

    public Multimap<Player, Card> cards() {
        return cards;
    }

    @Override
    public <T> T visit(IGameEventVisitor<T> visitor) {
        return visitor.visit(this);
    }

    @Override
    public int hashCode() {
        return cards != null ? cards.hashCode() : 0;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        GameEventAnteCardsSelected that = (GameEventAnteCardsSelected) obj;
        return java.util.Objects.equals(cards, that.cards);
    }

    @Override
    public String toString() {
        return "GameEventAnteCardsSelected[cards=" + cards + "]";
    }
}