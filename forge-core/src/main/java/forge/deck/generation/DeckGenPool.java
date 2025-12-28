package forge.deck.generation;

import forge.item.PaperCard;
import forge.item.PaperCardPredicates;
import forge.util.IterableUtil;

import java.util.HashMap;
import java.util.Map;
import forge.util.function.Predicate;

public class DeckGenPool implements IDeckGenPool {
    private final Map<String, PaperCard> cards = new HashMap<>();

    public DeckGenPool() {
    }
    public DeckGenPool(Iterable<PaperCard> cc) {
        addAll(cc);
    }

    public void add(PaperCard c) {
        cards.put(c.getName(), c);
    }
    public void addAll(Iterable<PaperCard> cc) {
        for (PaperCard c : cc) {
            add(c);
        }
    }

    public int size() {
        return cards.size();
    }

    @Override
    public PaperCard getCard(String name) {
        return cards.get(name);
    }

    @Override
    public PaperCard getCard(String name, String edition) {
        // iOS compatibility: Replace Stream API with traditional loop
        Predicate<PaperCard> filter = PaperCardPredicates.printedInSet(edition).and(PaperCardPredicates.name(name));
        for (PaperCard c : cards.values()) {
            if (filter.test(c)) {
                return c;
            }
        }
        return getCard(name);
    }

    @Override
    public PaperCard getCard(String name, String edition, int artIndex) {
        return getCard(name, edition);
    }

    public boolean contains(PaperCard card) {
        return contains(card.getName());
    }

    @Override
    public boolean contains(String name) {
        return cards.containsKey(name);
    }

    @Override
    public Iterable<PaperCard> getAllCards() {
        return cards.values();
    }

    @Override
    public Iterable<PaperCard> getAllCards(Predicate<PaperCard> filter) {
        return IterableUtil.filter(getAllCards(), filter);
    }
}
