package forge.gui.events;

import forge.gamemodes.match.NextGameDecision;
import forge.player.PlayerControllerHuman;

public class UiEventNextGameDecision implements UiEvent {
    private final PlayerControllerHuman controller;
    private final NextGameDecision decision;

    public UiEventNextGameDecision(PlayerControllerHuman controller, NextGameDecision decision) {
        this.controller = controller;
        this.decision = decision;
    }

    public PlayerControllerHuman controller() {
        return controller;
    }

    public NextGameDecision decision() {
        return decision;
    }



    @Override
    public <T> T visit(IUiEventVisitor<T> visitor) {
        return visitor.visit(this);
    }
    @Override
    public int hashCode() {
        int result = 17;
        result = 31 * result + (controller != null ? controller.hashCode() : 0);
        result = 31 * result + (decision != null ? decision.hashCode() : 0);
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        UiEventNextGameDecision that = (UiEventNextGameDecision) obj;
        return java.util.Objects.equals(controller, that.controller) &&
               java.util.Objects.equals(decision, that.decision);
    }

    @Override
    public String toString() {
        return "UiEventNextGameDecision[controller=" + controller + ", decision=" + decision + "]";
    }
}
