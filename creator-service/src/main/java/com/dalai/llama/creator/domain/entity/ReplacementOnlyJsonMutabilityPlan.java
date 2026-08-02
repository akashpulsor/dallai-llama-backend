package com.dalai.llama.creator.domain.entity;

import org.hibernate.type.descriptor.java.ImmutableMutabilityPlan;

/**
 * Avoids Hibernate's serialize/deserialize snapshot for large JSON values.
 *
 * Fields using this plan must replace their Map/List through the entity setter
 * instead of mutating the current value in place.
 */
public final class ReplacementOnlyJsonMutabilityPlan extends ImmutableMutabilityPlan<Object> {
}
