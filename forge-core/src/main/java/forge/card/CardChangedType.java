/*
 * Forge: Play Magic: the Gathering.
 * Copyright (C) 2011  Forge Team
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package forge.card;

import java.util.Set;

import com.google.common.collect.Lists;

import forge.card.CardType.CoreType;
import forge.util.IterableUtil;

public class CardChangedType implements ICardChangedType {
    private final CardTypeView addType;
    private final CardTypeView removeType;
    private final boolean addAllCreatureTypes;
    private final Set<RemoveType> remove;

    public CardChangedType(CardTypeView addType, CardTypeView removeType, boolean addAllCreatureTypes, Set<RemoveType> remove) {
        this.addType = addType;
        this.removeType = removeType;
        this.addAllCreatureTypes = addAllCreatureTypes;
        this.remove = remove;
    }

    public CardTypeView addType() {
        return addType;
    }

    public CardTypeView removeType() {
        return removeType;
    }

    public boolean addAllCreatureTypes() {
        return addAllCreatureTypes;
    }

    public Set<RemoveType> remove() {
        return remove;
    }

    public final boolean isRemoveSuperTypes() {
        return remove.contains(RemoveType.SuperTypes);
    }

    public final boolean isRemoveCardTypes() {
        return remove.contains(RemoveType.CardTypes);
    }

    public final boolean isRemoveSubTypes() {
        return remove.contains(RemoveType.SubTypes);
    }

    @Override
    public final boolean isRemoveLandTypes() {
        return remove.contains(RemoveType.LandTypes);
    }

    public final boolean isRemoveCreatureTypes() {
        return remove.contains(RemoveType.CreatureTypes);
    }

    public final boolean isRemoveArtifactTypes() {
        return remove.contains(RemoveType.ArtifactTypes);
    }

    public final boolean isRemoveEnchantmentTypes() {
        return remove.contains(RemoveType.EnchantmentTypes);
    }

    @Override
    public CardType applyChanges(CardType newType) {
        if (isRemoveCardTypes()) {
            // 205.1a However, an object with either the instant or sorcery card type retains that type.
            newType.coreTypes.retainAll(CoreType.spellTypes);
        }
        if (isRemoveSuperTypes()) {
            newType.supertypes.clear();
        }
        if (isRemoveSubTypes()) {
            newType.subtypes.clear();
        } else if (!newType.subtypes.isEmpty()) {
            if (isRemoveLandTypes()) {
                IterableUtil.removeIf(newType.subtypes, CardType::isALandType);
            }
            if (isRemoveCreatureTypes()) {
                IterableUtil.removeIf(newType.subtypes, CardType::isACreatureType);
                // need to remove AllCreatureTypes too when removing creature Types
                newType.allCreatureTypes = false;
            }
            if (isRemoveArtifactTypes()) {
                IterableUtil.removeIf(newType.subtypes, CardType::isAnArtifactType);
            }
            if (isRemoveEnchantmentTypes()) {
                IterableUtil.removeIf(newType.subtypes, CardType::isAnEnchantmentType);
            }
        }
        if (removeType() != null) {
            newType.removeAll(removeType());
        }
        if (addType() != null) {
            newType.addAll(addType());
            if (addType().hasAllCreatureTypes()) {
                newType.allCreatureTypes = true;
            }
        }
        if (addAllCreatureTypes()) {
            newType.allCreatureTypes = true;
        }
        // remove specific creature types from all creature types
        if (removeType() != null && newType.allCreatureTypes) {
            newType.excludedCreatureSubtypes.addAll(Lists.newArrayList(IterableUtil.filter(removeType(), CardType::isACreatureType)));
        }
        return newType;
    }

    @Override
    public int hashCode() {
        int result = 17;
        result = 31 * result + (addType != null ? addType.hashCode() : 0);
        result = 31 * result + (removeType != null ? removeType.hashCode() : 0);
        result = 31 * result + (addAllCreatureTypes ? 1 : 0);
        result = 31 * result + (remove != null ? remove.hashCode() : 0);
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        CardChangedType that = (CardChangedType) obj;
        return addAllCreatureTypes == that.addAllCreatureTypes &&
               java.util.Objects.equals(addType, that.addType) &&
               java.util.Objects.equals(removeType, that.removeType) &&
               java.util.Objects.equals(remove, that.remove);
    }

    @Override
    public String toString() {
        return "CardChangedType[addType=" + addType + ", removeType=" + removeType +
               ", addAllCreatureTypes=" + addAllCreatureTypes + ", remove=" + remove + "]";
    }
}
