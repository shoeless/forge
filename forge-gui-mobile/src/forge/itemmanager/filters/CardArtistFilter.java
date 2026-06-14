package forge.itemmanager.filters;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

import forge.StaticData;
import forge.item.PaperCard;
import forge.itemmanager.ItemManager;

import forge.util.function.Predicate;

/**
 * Quick combo-box filter that narrows the card pool to a single artist.
 * The list of artists is built once (across all printings) and cached.
 * Note: there are thousands of artists; for type-ahead use the Advanced
 * Search (CARD_ARTIST) instead — this combo box is the literal "filter by
 * artist" control and pairs with the ARTIST group/sort options.
 */
public class CardArtistFilter extends ComboBoxFilter<PaperCard, String> {
    private static List<String> cachedArtists;

    public CardArtistFilter(ItemManager<? super PaperCard> itemManager0) {
        super("Any Artist", getAllArtists(), itemManager0);
    }

    @Override
    public ItemFilter<PaperCard> createCopy() {
        CardArtistFilter copy = new CardArtistFilter(itemManager);
        copy.filterValue = filterValue;
        return copy;
    }

    @Override
    protected String getDisplayText(String value) {
        return value;
    }

    @Override
    protected Predicate<PaperCard> buildPredicate() {
        return input -> {
            if (filterValue == null) {
                return true;
            }
            return filterValue.equals(input.getArtist());
        };
    }

    private static List<String> getAllArtists() {
        if (cachedArtists == null) {
            TreeSet<String> artists = new TreeSet<>(); //sorted + unique
            for (PaperCard pc : StaticData.instance().getCommonCards().getAllCards()) {
                String artist = pc.getArtist();
                if (artist != null && !artist.isEmpty()) {
                    artists.add(artist);
                }
            }
            cachedArtists = new ArrayList<>(artists);
        }
        return cachedArtists;
    }
}
