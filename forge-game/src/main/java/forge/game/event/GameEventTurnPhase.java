package forge.game.event;

import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.util.Lang;
import forge.util.TextUtil;

public class GameEventTurnPhase implements GameEvent {
    private final Player playerTurn;
    private final PhaseType phase;
    private final String phaseDesc;

    public GameEventTurnPhase(Player playerTurn, PhaseType phase, String phaseDesc) {
        this.playerTurn = playerTurn;
        this.phase = phase;
        this.phaseDesc = phaseDesc;
    }

    public Player playerTurn() {
        return playerTurn;
    }

    public PhaseType phase() {
        return phase;
    }

    public String phaseDesc() {
        return phaseDesc;
    }



    @Override
    public <T> T visit(IGameEventVisitor<T> visitor) {
        return visitor.visit(this);
    }

    @Override
    public String toString() {
        String playerName = Lang.getInstance().getPossesive(playerTurn.getName());
        return TextUtil.concatWithSpace(playerName,"turn,", phaseDesc+phase.nameForUi, "phase");
    }
    @Override
    public int hashCode() {
        int result = 17;
        result = 31 * result + (playerTurn != null ? playerTurn.hashCode() : 0);
        result = 31 * result + (phase != null ? phase.hashCode() : 0);
        result = 31 * result + (phaseDesc != null ? phaseDesc.hashCode() : 0);
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        GameEventTurnPhase that = (GameEventTurnPhase) obj;
        return java.util.Objects.equals(playerTurn, that.playerTurn) &&
               java.util.Objects.equals(phase, that.phase) &&
               java.util.Objects.equals(phaseDesc, that.phaseDesc);
    }
}
