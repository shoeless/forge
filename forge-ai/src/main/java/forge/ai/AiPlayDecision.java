package forge.ai;

public enum AiPlayDecision {
    // Play decision reasons
    WillPlay,
    MandatoryPlay,
    PlayToEmptyHand,
    ImpactCombat,
    ResponseToStackResolve,
    AddBoardPresence,
    Removal,
    Tempo,
    CardAdvantage,

    // Play later decisions
    WaitForCombat,
    WaitForMain2,
    WaitForEndOfTurn,
    StackNotEmpty,
    AnotherTime,

    // Don't play decision reasons
    CantPlaySa,
    CantPlayAi,
    CantAfford,
    CantAffordX,
    DoesntImpactCombat,
    DoesntImpactGame,
    MissingLogic,
    MissingNeededCards,
    TimingRestrictions,
    MissingPhaseRestrictions,
    ConditionsNotMet,
    NeedsToPlayCriteriaNotMet,
    StopRunawayActivations,
    TargetingFailed,
    CostNotAcceptable,
    LifeInDanger,
    WouldDestroyLegend,
    WouldDestroyOtherPlaneswalker,
    WouldBecomeZeroToughnessCreature,
    WouldDestroyWorldEnchantment,
    BadEtbEffects,
    CurseEffects;

    public boolean willingToPlay() {
        // iOS compatibility: Replace Java 14+ switch expression with traditional switch
        switch (this) {
            case WillPlay:
            case MandatoryPlay:
            case PlayToEmptyHand:
            case AddBoardPresence:
            case ImpactCombat:
            case ResponseToStackResolve:
            case Removal:
            case Tempo:
            case CardAdvantage:
                return true;
            default:
                return false;
        }
    }
}