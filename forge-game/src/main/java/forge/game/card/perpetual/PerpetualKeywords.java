package forge.game.card.perpetual;

import java.util.List;

import forge.game.card.Card;

public class PerpetualKeywords implements PerpetualInterface {
    private final long timestamp;
    private final List<String> addKeywords;
    private final List<String> removeKeywords;
    private final boolean removeAll;

    public PerpetualKeywords(long timestamp, List<String> addKeywords, List<String> removeKeywords, boolean removeAll) {
        this.timestamp = timestamp;
        this.addKeywords = addKeywords;
        this.removeKeywords = removeKeywords;
        this.removeAll = removeAll;
    }

    public long timestamp() {
        return timestamp;
    }

    public List<String> addKeywords() {
        return addKeywords;
    }

    public List<String> removeKeywords() {
        return removeKeywords;
    }

    public boolean removeAll() {
        return removeAll;
    }

    @Override
    public long getTimestamp() {
        return timestamp;
    }

    @Override
    public void applyEffect(Card c) {
        c.addChangedCardKeywords(addKeywords, removeKeywords, removeAll, timestamp, null);
    }

    @Override
    public int hashCode() {
        int result = 17;
        result = 31 * result + (int)(timestamp ^ (timestamp >>> 32));
        result = 31 * result + (addKeywords != null ? addKeywords.hashCode() : 0);
        result = 31 * result + (removeKeywords != null ? removeKeywords.hashCode() : 0);
        result = 31 * result + (removeAll ? 1 : 0);
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        PerpetualKeywords that = (PerpetualKeywords) obj;
        return timestamp == that.timestamp &&
               removeAll == that.removeAll &&
               java.util.Objects.equals(addKeywords, that.addKeywords) &&
               java.util.Objects.equals(removeKeywords, that.removeKeywords);
    }

    @Override
    public String toString() {
        return "PerpetualKeywords[timestamp=" + timestamp + ", addKeywords=" + addKeywords +
               ", removeKeywords=" + removeKeywords + ", removeAll=" + removeAll + "]";
    }
}
