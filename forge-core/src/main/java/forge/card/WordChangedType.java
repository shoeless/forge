package forge.card;

public final class WordChangedType implements ICardChangedType {
    private final String oldWord;
    private final String newWord;

    public WordChangedType(String oldWord, String newWord) {
        this.oldWord = oldWord;
        this.newWord = newWord;
    }

    public String oldWord() {
        return oldWord;
    }

    public String newWord() {
        return newWord;
    }

    @Override
    public CardType applyChanges(CardType newType) {
        if (newType.hasStringType(oldWord)) {
            newType.subtypes.remove(oldWord);
            newType.subtypes.add(newWord);
        }
        return newType;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof WordChangedType)) return false;
        WordChangedType other = (WordChangedType) obj;
        return (oldWord != null ? oldWord.equals(other.oldWord) : other.oldWord == null)
            && (newWord != null ? newWord.equals(other.newWord) : other.newWord == null);
    }

    @Override
    public int hashCode() {
        int result = oldWord != null ? oldWord.hashCode() : 0;
        result = 31 * result + (newWord != null ? newWord.hashCode() : 0);
        return result;
    }

    @Override
    public String toString() {
        return "WordChangedType[oldWord=" + oldWord + ", newWord=" + newWord + "]";
    }
}
