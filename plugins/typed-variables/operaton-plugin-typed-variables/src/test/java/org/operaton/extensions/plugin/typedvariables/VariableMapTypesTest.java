/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.typedvariables;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.operaton.extensions.plugin.typedvariables.TestVariables.CREATED;
import static org.operaton.extensions.plugin.typedvariables.TestVariables.DISCOUNT;
import static org.operaton.extensions.plugin.typedvariables.TestVariables.ORDER_ID;
import static org.operaton.extensions.plugin.typedvariables.TestVariables.PAID;
import static org.operaton.extensions.plugin.typedvariables.TestVariables.QUANTITY;
import static org.operaton.extensions.plugin.typedvariables.TestVariables.STATUS;
import static org.operaton.extensions.plugin.typedvariables.TestVariables.TOTAL_CENTS;
import static org.operaton.extensions.plugin.typedvariables.TestVariables.TRACKING;

import java.util.Date;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.variable.VariableMap;
import org.operaton.bpm.engine.variable.Variables;
import org.operaton.extensions.plugin.typedvariables.TestVariables.Status;

/** Codecs, conversions and the failure messages, on a plain variable map (no engine needed). */
class VariableMapTypesTest {

    @Test
    void everyScalarTypeRoundTripsAndKeepsItsEngineType() {
        Date now = new Date();
        UUID id = UUID.randomUUID();
        VariableMap map = TypedVariables.builder()
                .set(ORDER_ID, "A-1")
                .set(QUANTITY, 3)
                .set(TOTAL_CENTS, 4_000_000_000L)
                .set(DISCOUNT, 0.5)
                .set(PAID, false)
                .set(CREATED, now)
                .set(TRACKING, id)
                .set(STATUS, Status.NEW)
                .build();

        assertThat(ORDER_ID.get(map)).isEqualTo("A-1");
        assertThat(QUANTITY.get(map)).isEqualTo(3);
        assertThat(TOTAL_CENTS.get(map)).isEqualTo(4_000_000_000L);
        assertThat(DISCOUNT.get(map)).isEqualTo(0.5);
        assertThat(PAID.get(map)).isFalse();
        assertThat(CREATED.get(map)).isEqualTo(now);
        assertThat(TRACKING.get(map)).isEqualTo(id);
        assertThat(STATUS.get(map)).isEqualTo(Status.NEW);
        assertThat(map.getValueTyped("quantity").getType().getName()).isEqualTo("integer");
        assertThat(map.getValueTyped("totalCents").getType().getName()).isEqualTo("long");
        assertThat(map.getValueTyped("tracking").getValue()).isEqualTo(id.toString());
    }

    @Test
    void integralNumbersAreReadAcrossTheirWidthsWhenLossless() {
        VariableMap map = Variables.createVariables()
                .putValueTyped("totalCents", Variables.integerValue(7))
                .putValueTyped("quantity", Variables.longValue(9L))
                .putValueTyped("discount", Variables.integerValue(1));

        assertThat(TOTAL_CENTS.get(map)).isEqualTo(7L);
        assertThat(QUANTITY.get(map)).isEqualTo(9);
        // an integer is not silently turned into a floating point number
        assertThatThrownBy(() -> DISCOUNT.get(map)).isInstanceOf(WrongVariableTypeException.class);
    }

    @Test
    void aLongOutsideTheIntRangeIsNotNarrowed() {
        VariableMap map = Variables.createVariables().putValueTyped("quantity", Variables.longValue(5_000_000_000L));

        assertThatThrownBy(() -> QUANTITY.get(map))
                .isInstanceOf(WrongVariableTypeException.class)
                .hasMessageContaining("declared as Integer")
                .hasMessageContaining("java.lang.Long");
    }

    @Test
    void invalidStoredTextForUuidAndEnumFailsWithTheCause() {
        VariableMap map =
                Variables.createVariables().putValue("tracking", "not-a-uuid").putValue("status", "SHIPPED");

        assertThatThrownBy(() -> TRACKING.get(map))
                .isInstanceOf(WrongVariableTypeException.class)
                .hasMessageContaining("not a UUID")
                .hasCauseInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> STATUS.get(map))
                .isInstanceOf(WrongVariableTypeException.class)
                .hasMessageContaining("not a constant of " + Status.class.getName());
    }

    @Test
    void nullIsRefusedOnWriteAndRemoveIsTheWayToClear() {
        VariableMap map = TypedVariables.builder().set(ORDER_ID, "A-1").build();

        assertThatThrownBy(() -> ORDER_ID.set(map, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cannot set variable 'orderId' to null")
                .hasMessageContaining("remove()");
        assertThat(ORDER_ID.remove(map)).isSameAs(map);
        assertThat(ORDER_ID.isPresent(map)).isFalse();
    }

    @Test
    void builderSkipsNullsOnRequestAndReturnsIndependentMaps() {
        VariableMapBuilder builder =
                TypedVariables.builder().set(ORDER_ID, "A-1").setIfNotNull(PAID, null);
        VariableMap first = builder.build();
        VariableMap second = builder.set(QUANTITY, 1).build();

        assertThat(first.keySet()).containsExactly("orderId");
        assertThat(second.keySet()).containsExactlyInAnyOrder("orderId", "quantity");
    }

    @Test
    void aVariableMapHasNoLocalScope() {
        VariableMap map = TypedVariables.builder().set(ORDER_ID, "A-1").build();

        assertThatThrownBy(() -> ORDER_ID.from(map).getLocal()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> ORDER_ID.on(map).setLocal("x")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void blankVariableNamesAreRejectedAtDeclaration() {
        assertThatThrownBy(() -> TypedVariables.stringVariable(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be blank");
        assertThatThrownBy(() -> TypedVariables.stringVariable(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void aCustomCodecDeclaresATypeOfYourOwn() {
        VariableFactory<java.time.LocalDate> day =
                TypedVariables.variable("day", new VariableCodec<java.time.LocalDate>() {
                    @Override
                    public String describeType() {
                        return "LocalDate";
                    }

                    @Override
                    public org.operaton.bpm.engine.variable.value.TypedValue encode(
                            String variableName, java.time.LocalDate value) {
                        return Variables.stringValue(value.toString());
                    }

                    @Override
                    public java.time.LocalDate decode(
                            String variableName, org.operaton.bpm.engine.variable.value.TypedValue value) {
                        return java.time.LocalDate.parse((String) value.getValue());
                    }
                });
        VariableMap map = TypedVariables.builder()
                .set(day, java.time.LocalDate.of(2026, 5, 17))
                .build();

        assertThat(map.getValue("day", String.class)).isEqualTo("2026-05-17");
        assertThat(day.get(map)).isEqualTo(java.time.LocalDate.of(2026, 5, 17));
    }
}
