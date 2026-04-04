package xyz.kwahson.core.config;

import java.util.function.DoubleConsumer;
import java.util.function.DoubleFunction;
import java.util.function.IntConsumer;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * Generic range slider for config screens. Maps the 0-1 slider position
 * to a double range, formats the display via a caller-provided function,
 * and reports changes via a callback.
 */
public class ConfigSlider extends AbstractSliderButton {

  private final double min;
  private final double max;
  private final DoubleFunction<String> formatter;
  private final DoubleConsumer onChange;

  public ConfigSlider(int width, double min, double max, double initial,
                      DoubleFunction<String> formatter, DoubleConsumer onChange) {
    super(0, 0, width, 20, Component.empty(), normalizeInitial(initial, min, max));
    this.min = min;
    this.max = max;
    this.formatter = formatter;
    this.onChange = onChange;
    updateMessage();
  }

  /** Normalizes initial value to [0,1], guarding against zero-width range. */
  private static double normalizeInitial(double initial, double min, double max) {
    double range = max - min;
    if (range == 0) return 0;
    return Mth.clamp((initial - min) / range, 0.0, 1.0);
  }

  /** The denormalized value in the [min, max] range. */
  public double realValue() {
    return Mth.lerp(this.value, min, max);
  }

  @Override
  protected void updateMessage() {
    setMessage(Component.literal(formatter.apply(realValue())));
  }

  @Override
  protected void applyValue() {
    onChange.accept(realValue());
  }

  /** Creates an integer slider that snaps to a step size and clamps to [min, max]. */
  public static ConfigSlider ofInt(int width, String label, String suffix,
                                   int min, int max, int step, int initial,
                                   IntConsumer onChange) {
    java.util.function.IntUnaryOperator snap = v -> {
      int snapped = Math.round((float) v / step) * step;
      return Mth.clamp(snapped, min, max);
    };
    return new ConfigSlider(width, min, max, initial,
        v -> label + ": " + snap.applyAsInt((int) Math.round(v)) + suffix,
        v -> onChange.accept(snap.applyAsInt((int) Math.round(v))));
  }

  public static ConfigSlider ofPercent(int width, String label,
                                       double min, double max, double initial,
                                       DoubleConsumer onChange) {
    return new ConfigSlider(width, min, max, initial,
        v -> label + ": " + Math.round(v * 100) + "%",
        onChange);
  }
}
