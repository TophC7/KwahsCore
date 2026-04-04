package xyz.kwahson.core.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntFunction;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.tabs.GridLayoutTab;
import net.minecraft.client.gui.layouts.LayoutElement;
import net.minecraft.client.gui.layouts.SpacerElement;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * A config screen tab with a two-column grid layout and helper methods
 * for common widget types (cycle buttons, sliders, toggles, sections).
 *
 * <p>Wraps vanilla's {@link GridLayoutTab}, exposing the protected layout
 * field and providing a fluent API for building config UIs.
 *
 * <p>When constructed with common-spec paths, widgets backed by those
 * config values are tracked so the screen can grey them out on
 * dedicated servers.
 */
public class ConfigTab extends GridLayoutTab {

  private static final int COL_WIDTH = 200;
  private static final int COL_GAP = 12;
  private static final int SECTION_TITLE_COLOR = 0xFFFFFF;

  private final Set<List<String>> commonPaths;
  private final List<AbstractWidget> commonWidgets = new ArrayList<>();
  private int row = 0;

  public ConfigTab(String title) {
    this(title, Set.of());
  }

  ConfigTab(String title, Set<List<String>> commonPaths) {
    super(Component.literal(title));
    this.layout.rowSpacing(4).columnSpacing(COL_GAP);
    this.commonPaths = commonPaths;
  }

  /** Returns widgets backed by common-spec config values. */
  List<AbstractWidget> getCommonWidgets() {
    return Collections.unmodifiableList(commonWidgets);
  }

  // LAYOUT HELPERS //

  public ConfigTab sections(String left, String right) {
    layout.addChild(sectionTitle(left), row, 0);
    layout.addChild(sectionTitle(right), row, 1);
    row++;
    return this;
  }

  public ConfigTab section(String title) {
    layout.addChild(sectionTitle(title), row, 0, 1, 2);
    row++;
    return this;
  }

  public ConfigTab spacer(int height) {
    layout.addChild(SpacerElement.height(height), row, 0, 1, 2);
    row++;
    return this;
  }

  public ConfigTab left(LayoutElement widget) {
    layout.addChild(widget, row, 0);
    return this;
  }

  public ConfigTab right(LayoutElement widget) {
    layout.addChild(widget, row, 1);
    return this;
  }

  public ConfigTab nextRow() {
    row++;
    return this;
  }

  // WIDGET FACTORIES //

  public CycleButton<Boolean> toggle(String label, ModConfigSpec.BooleanValue configVal) {
    var btn = CycleButton.onOffBuilder(SafeConfig.getBool(configVal, false))
        .create(0, 0, COL_WIDTH, 20, Component.literal(label),
            (b, val) -> configVal.set(val));
    return trackIfCommon(btn, configVal);
  }

  public <E extends Enum<E>> CycleButton<E> enumButton(
      String label, ModConfigSpec.EnumValue<E> configVal,
      Function<E, String> labelFn, E[] values) {
    E initial = SafeConfig.getEnum(configVal, values[0]);
    var btn = CycleButton.<E>builder(v -> Component.literal(labelFn.apply(v)))
        .withValues(values)
        .withInitialValue(initial)
        .create(0, 0, COL_WIDTH, 20, Component.literal(label),
            (b, val) -> configVal.set(val));
    return trackIfCommon(btn, configVal);
  }

  public CycleButton<Integer> intCycle(
      String label, ModConfigSpec.IntValue configVal,
      IntFunction<String> formatter, Integer[] values) {
    int initial = SafeConfig.getInt(configVal, values[0]);
    var btn = CycleButton.<Integer>builder(v -> Component.literal(formatter.apply(v)))
        .withValues(values)
        .withInitialValue(initial)
        .create(0, 0, COL_WIDTH, 20, Component.literal(label),
            (b, val) -> configVal.set(val));
    return trackIfCommon(btn, configVal);
  }

  /**
   * Generic cycle button for arbitrary value types. Use this when the value
   * is not backed by a ModConfigSpec enum or int (e.g., record presets).
   */
  public <T> CycleButton<T> cycle(
      String label, T initial, Function<T, String> labelFn,
      List<T> values, BiConsumer<CycleButton<T>, T> onChange) {
    return CycleButton.<T>builder(v -> Component.literal(labelFn.apply(v)))
        .withValues(values)
        .withInitialValue(initial)
        .create(0, 0, COL_WIDTH, 20, Component.literal(label),
            onChange::accept);
  }

  public ConfigSlider intSlider(String label, String suffix,
                                int min, int max, int step,
                                ModConfigSpec.IntValue configVal) {
    var slider = ConfigSlider.ofInt(COL_WIDTH, label, suffix, min, max, step,
        SafeConfig.getInt(configVal, (min + max) / 2), val -> configVal.set(val));
    return trackIfCommon(slider, configVal);
  }

  public ConfigSlider percentSlider(String label, double min, double max,
                                    ModConfigSpec.DoubleValue configVal) {
    var slider = ConfigSlider.ofPercent(COL_WIDTH, label, min, max,
        SafeConfig.getDouble(configVal, (min + max) / 2), val -> configVal.set(val));
    return trackIfCommon(slider, configVal);
  }

  public Button button(String label, Consumer<Button> onPress) {
    return Button.builder(Component.literal(label), onPress::accept)
        .width(COL_WIDTH).build();
  }

  // INTERNALS //

  /**
   * If the config value belongs to the common spec, track the widget
   * so the screen can grey it out on dedicated servers.
   */
  private <W extends AbstractWidget> W trackIfCommon(W widget,
      ModConfigSpec.ConfigValue<?> configVal) {
    if (!commonPaths.isEmpty() && commonPaths.contains(configVal.getPath())) {
      commonWidgets.add(widget);
    }
    return widget;
  }

  private StringWidget sectionTitle(String text) {
    return new StringWidget(COL_WIDTH, 14, Component.literal(text),
        Minecraft.getInstance().font)
        .setColor(SECTION_TITLE_COLOR)
        .alignLeft();
  }
}
