package xyz.kwahson.core.config;

import com.electronwill.nightconfig.core.UnmodifiableConfig;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.tabs.Tab;
import net.minecraft.client.gui.components.tabs.TabManager;
import net.minecraft.client.gui.components.tabs.TabNavigationBar;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * A tabbed config screen built on vanilla's tab navigation system.
 *
 * <p>Handles all the boilerplate: tab bar, content area, Done button,
 * keyboard navigation (Ctrl+1/2/Tab), and saving the config spec on close.
 *
 * <p>Supports dual specs (CLIENT + COMMON). When both are provided, widgets
 * backed by COMMON config values are automatically greyed out on dedicated
 * servers where the client cannot change them.
 *
 * <p>Usage:
 * <pre>{@code
 * KwahsConfigScreen.builder("My Mod Settings", parent, CLIENT_SPEC, COMMON_SPEC)
 *     .tab("General", tab -> {
 *         tab.sections("Gameplay", "Display");
 *         tab.left(tab.toggle("Mute", CLIENT_MUTE));
 *         tab.right(tab.intSlider("Range", "m", 1, 100, 5, COMMON_RANGE));
 *         tab.nextRow();
 *     })
 *     .build();
 * }</pre>
 */
public class KwahsConfigScreen extends Screen {

  private static final int TAB_NAV_HEIGHT = 24;
  private static final int BOTTOM_MARGIN = 36;
  private static final int FOOTNOTE_COLOR = 0xFF808080;

  private final Screen parent;
  private final ModConfigSpec spec;
  private final ModConfigSpec commonSpec;
  private final List<ConfigTab> tabs;
  private TabNavigationBar tabNavBar;
  private TabManager tabManager;
  private boolean showServerNote;

  private KwahsConfigScreen(String title, Screen parent, ModConfigSpec spec,
                            ModConfigSpec commonSpec, List<ConfigTab> tabs) {
    super(Component.literal(title));
    this.parent = parent;
    this.spec = spec;
    this.commonSpec = commonSpec;
    this.tabs = tabs;
  }

  @Override
  protected void init() {
    this.tabManager = new TabManager(this::addRenderableWidget, this::removeWidget);

    this.tabNavBar = TabNavigationBar.builder(this.tabManager, this.width)
        .addTabs(tabs.toArray(new Tab[0]))
        .build();
    this.addRenderableWidget(this.tabNavBar);
    this.tabNavBar.arrangeElements();

    addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, btn -> onClose())
        .bounds(this.width / 2 - 100, this.height - 28, 200, 20).build());

    ScreenRectangle contentArea = new ScreenRectangle(
        0, TAB_NAV_HEIGHT,
        this.width, this.height - TAB_NAV_HEIGHT - BOTTOM_MARGIN);
    this.tabManager.setTabArea(contentArea);

    this.tabNavBar.selectTab(0, false);

    // Grey out common-spec widgets when connected to a dedicated server
    showServerNote = false;
    if (commonSpec != null && isOnDedicatedServer()) {
      for (ConfigTab tab : tabs) {
        for (var widget : tab.getCommonWidgets()) {
          widget.active = false;
          showServerNote = true;
        }
      }
    }
  }

  @Override
  public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    super.render(graphics, mouseX, mouseY, partialTick);
    this.tabNavBar.render(graphics, mouseX, mouseY, partialTick);

    if (showServerNote) {
      graphics.drawString(this.font,
          "* Greyed settings are controlled by the server",
          4, this.height - 8, FOOTNOTE_COLOR, false);
    }
  }

  @Override
  public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
    if (this.tabNavBar.keyPressed(keyCode)) return true;
    return super.keyPressed(keyCode, scanCode, modifiers);
  }

  @Override
  public void onClose() {
    spec.save();
    if (commonSpec != null && !isOnDedicatedServer()) commonSpec.save();
    this.minecraft.setScreen(parent);
  }

  private static boolean isOnDedicatedServer() {
    Minecraft mc = Minecraft.getInstance();
    return mc.level != null && !mc.hasSingleplayerServer();
  }

  // -- Builder --

  /** Single-spec builder for mods with only one config type. */
  public static Builder builder(String title, Screen parent, ModConfigSpec spec) {
    return new Builder(title, parent, spec, null);
  }

  /** Dual-spec builder. Common-spec widgets are greyed out on dedicated servers. */
  public static Builder builder(String title, Screen parent,
                                ModConfigSpec clientSpec, ModConfigSpec commonSpec) {
    return new Builder(title, parent, clientSpec, commonSpec);
  }

  public static class Builder {
    private final String title;
    private final Screen parent;
    private final ModConfigSpec spec;
    private final ModConfigSpec commonSpec;
    private final Set<List<String>> commonPaths;
    private final List<ConfigTab> tabs = new ArrayList<>();

    private Builder(String title, Screen parent, ModConfigSpec spec,
                    ModConfigSpec commonSpec) {
      this.title = title;
      this.parent = parent;
      this.spec = spec;
      this.commonSpec = commonSpec;
      this.commonPaths = commonSpec != null ? collectCommonPaths(commonSpec) : Set.of();
    }

    public Builder tab(String name, Consumer<ConfigTab> builder) {
      ConfigTab tab = new ConfigTab(name, commonPaths);
      builder.accept(tab);
      tabs.add(tab);
      return this;
    }

    public KwahsConfigScreen build() {
      return new KwahsConfigScreen(title, parent, spec, commonSpec, tabs);
    }

    /**
     * Walks a ModConfigSpec's value tree and collects every ConfigValue path.
     * Used to identify which widgets belong to the common spec so they can
     * be greyed out on dedicated servers.
     */
    private static Set<List<String>> collectCommonPaths(ModConfigSpec spec) {
      Set<List<String>> paths = new HashSet<>();
      walkConfig(spec.getValues().valueMap().values(), paths);
      return paths;
    }

    private static void walkConfig(Collection<Object> values, Set<List<String>> out) {
      for (Object value : values) {
        if (value instanceof ModConfigSpec.ConfigValue<?> cv) {
          out.add(cv.getPath());
        } else if (value instanceof UnmodifiableConfig sub) {
          walkConfig(sub.valueMap().values(), out);
        }
      }
    }
  }
}
