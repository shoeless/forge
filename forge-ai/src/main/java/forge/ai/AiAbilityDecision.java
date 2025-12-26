package forge.ai;

public class AiAbilityDecision {
    private static int MIN_RATING = 30;
    private final int rating;
    private final AiPlayDecision decision;

    public AiAbilityDecision(int rating, AiPlayDecision decision) {
        this.rating = rating;
        this.decision = decision;
    }

    public int rating() {
        return rating;
    }

    public AiPlayDecision decision() {
        return decision;
    }

    public boolean willingToPlay() {
        return rating > MIN_RATING && decision.willingToPlay();
    }

    @Override
    public int hashCode() {
        int result = 17;
        result = 31 * result + rating;
        result = 31 * result + (decision != null ? decision.hashCode() : 0);
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        AiAbilityDecision that = (AiAbilityDecision) obj;
        return rating == that.rating &&
               java.util.Objects.equals(decision, that.decision);
    }

    @Override
    public String toString() {
        return "AiAbilityDecision[rating=" + rating + ", decision=" + decision + "]";
    }
}
