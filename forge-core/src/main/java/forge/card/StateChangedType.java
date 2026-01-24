package forge.card;

public final class StateChangedType implements ICardChangedType {
    private final CardTypeView type;

    public StateChangedType(CardTypeView type) {
        this.type = type;
    }

    public CardTypeView type() {
        return type;
    }

    @Override
    public CardType applyChanges(CardType newType) {
        return new CardType(type);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof StateChangedType)) return false;
        StateChangedType other = (StateChangedType) obj;
        return type != null ? type.equals(other.type) : other.type == null;
    }

    @Override
    public int hashCode() {
        return type != null ? type.hashCode() : 0;
    }

    @Override
    public String toString() {
        return "StateChangedType[type=" + type + "]";
    }
}
