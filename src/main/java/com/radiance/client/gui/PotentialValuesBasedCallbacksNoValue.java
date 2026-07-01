package com.radiance.client.gui;

import com.mojang.serialization.Codec;
import java.util.List;
import java.util.Optional;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.gui.components.CycleButton;

/**
 * 26.2: SimpleOption.CyclingCallbacks -> OptionInstance.CycleableValueSet. The custom
 * getWidgetCreator (which called GameOptions.write() + a changeCallback) is dropped -- the
 * default CycleableValueSet#createButton handles the widget, and the write/callback logic
 * moves to the OptionInstance's onValueUpdate at the construction site.
 */
@Environment(EnvType.CLIENT)
public record PotentialValuesBasedCallbacksNoValue<T>(List<T> values, Codec<T> codec) implements
    OptionInstance.CycleableValueSet<T> {

    @Override
    public Optional<T> validateValue(T value) {
        return this.values.contains(value) ? Optional.of(value) : Optional.empty();
    }

    @Override
    public CycleButton.ValueListSupplier<T> valueListSupplier() {
        return CycleButton.ValueListSupplier.create(this.values);
    }
}
