package forge.game.card;

import java.io.Serializable;

import forge.card.ICardFace;
import forge.util.CardTranslation;
import forge.util.ITranslatable;

public final class CardFaceView implements Serializable, ITranslatable, Comparable<CardFaceView> {
    private static final long serialVersionUID = 1874016432028306386L;

    private final String name;
    private final String displayName;

    public CardFaceView(String name, String displayName) {
        this.name = name;
        this.displayName = displayName;
    }

    public CardFaceView(ICardFace face) {
        this(face.getName(), face.getDisplayName());
    }

    public String name() {
        return this.name;
    }

    public String displayName() {
        return this.displayName;
    }

    @Override
    public String getName() {
        return this.name;
    }
    @Override
    public String getTranslatedName() {
        return CardTranslation.getTranslatedName(this.displayName);
    }
    @Override
    public String toString() {
        return name;
    }

    @Override
    public int compareTo(CardFaceView o) {
        return this.getName().compareTo(o.getName());
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof CardFaceView)) return false;
        CardFaceView other = (CardFaceView) obj;
        return name != null ? name.equals(other.name) : other.name == null;
    }

    @Override
    public int hashCode() {
        return this.name.hashCode();
    }
}
