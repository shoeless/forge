package forge.game.staticability;

import forge.game.Game;
import forge.game.GameEntity;
import forge.game.ability.AbilityUtils;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import forge.util.collect.FCollectionView;
import forge.util.maps.LinkedHashMapToAmount;
import forge.util.maps.MapToAmount;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class StaticAbilityMustAttack {

    public static List<GameEntity> entitiesMustAttack(final Card attacker) {
        final List<GameEntity> entityList = new ArrayList<>();
        final Game game = attacker.getGame();
        for (final Card ca : game.getCardsIn(ZoneType.STATIC_ABILITIES_SOURCE_ZONES)) {
            for (final StaticAbility stAb : ca.getStaticAbilities()) {
                if (!stAb.checkConditions(StaticAbilityMode.MustAttack)) {
                    continue;
                }
                if (stAb.matchesValidParam("ValidCreature", attacker)) {
                    if (stAb.hasParam("MustAttack")) {
                        List<GameEntity> def = AbilityUtils.getDefinedEntities(stAb.getHostCard(), stAb.getParam("MustAttack"), stAb);
                        for (GameEntity e : def) {
                            boolean skipEntity = false;
                            if (e instanceof Player) {
                                Player attackPl = (Player) e;
                                if (game.getPhaseHandler().isPlayerTurn(attackPl)) {
                                    skipEntity = true;
                                }
                            } else if (e instanceof Card) {
                                Card attackPw = (Card) e;
                                if (game.getPhaseHandler().isPlayerTurn(attackPw.getController())) {
                                    skipEntity = true;
                                }
                            }
                            if (skipEntity) {
                                // CR 506.2
                                continue;
                            }
                            entityList.add(e);
                        }
                    } else {
                        // if the list is only the attacker, the attacker must attack, but no specific entity
                        entityList.add(attacker);
                    }
                }
            }
        }
        return entityList;
    }

    public static List<Set<GameEntity>> mustAttackSpecific(final Player attackingPlayer, final FCollectionView<GameEntity> possibleDefenders) {
        List<Set<GameEntity>> defToAtt = new ArrayList<>();
        for (final Card ca : attackingPlayer.getGame().getCardsIn(ZoneType.STATIC_ABILITIES_SOURCE_ZONES)) {
            for (final StaticAbility stAb : ca.getStaticAbilities()) {
                if (!stAb.checkConditions(StaticAbilityMode.PlayerMustAttack)) {
                    continue;
                }
                if (!stAb.matchesValidParam("ValidPlayer", attackingPlayer)) {
                    continue;
                }
                Set<GameEntity> attackWithOne = new HashSet<>();
                for (GameEntity ge : possibleDefenders) {
                    if (stAb.matchesValidParam("MustAttack", ge)) {
                        attackWithOne.add(ge);
                    }
                }
                defToAtt.add(attackWithOne);
            }
        }
        return defToAtt;
    }

    // AttackRequirement static mode: "If <ValidCard> attacks, <ValidAttacker> also attacks if able."
    // Returns, for the given trigger creature (card, matched against ValidCard$), the set of other
    // creatures (matched against ValidAttacker$) that must also attack, with a count equal to the
    // number of matching static abilities (so the same creature forced by two sources counts twice).
    // This mirrors our engine's keyword-based causesToAttack model (MapToAmount<Card>) rather than
    // upstream's Multimap<Card, StaticAbility>, so it plugs straight into AttackRequirement.
    public static MapToAmount<Card> getAttackRequirements(final Card card, final Iterable<Card> other) {
        final MapToAmount<Card> result = new LinkedHashMapToAmount<>();
        for (final Card ca : card.getGame().getCardsIn(ZoneType.STATIC_ABILITIES_SOURCE_ZONES)) {
            for (final StaticAbility stAb : ca.getStaticAbilities()) {
                if (!stAb.checkConditions(StaticAbilityMode.AttackRequirement)) {
                    continue;
                }
                if (!stAb.matchesValidParam("ValidCard", card)) {
                    continue;
                }
                for (final Card co : other) {
                    if (stAb.matchesValidParam("ValidAttacker", co)) {
                        result.add(co, 1);
                    }
                }
            }
        }
        return result;
    }
}
