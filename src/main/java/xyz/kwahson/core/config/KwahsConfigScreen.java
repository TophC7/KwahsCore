package xyz.kwahson.core.config;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
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
 * <p>Usage:
 * <pre>{@code
 * KwahsConfigScreen.builder("My Mod Settings", parent, MY_SPEC)
 *     .tab("General", tab -> {
 *         tab.sections("Gameplay", "Display");
 *         tab.left(tab.toggle("Feature", MY_FEATURE));
 *         tab.right(tab.intSlider("Range", "m", 1, 100, 5, MY_RANGE));
 *         tab.nextRow();
 *     })
 *     .tab("Advanced", tab -> { ... })
 *     .build();
 * }</pre>
 */
public class KwahsConfigScreen extends Screen {

  private static final int TAB_NAV_HEIGHT = 24;
  private static final int BOTTOM_MARGIN = 36;

  private final Screen parent;
  private final ModConfigSpec spec;
  private final List<ConfigTab> tabs;
  private TabNavigationBar tabNavBar;
  private TabManager tabManager;

  private KwahsConfigScreen(String title, Screen parent, ModConfigSpec spec,
                            List<ConfigTab> tabs) {
    super(Component.literal(title));
    this.parent = parent;
    this.spec = spec;
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
  }

  @Override
  public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    super.render(graphics, mouseX, mouseY, partialTick);
    this.tabNavBar.render(graphics, mouseX, mouseY, partialTick);
  }

  @Override
  public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
    if (this.tabNavBar.keyPressed(keyCode)) return true;
    return super.keyPressed(keyCode, scanCode, modifiers);
  }

  @Override
  public void onClose() {
    spec.save();
    this.minecraft.setScreen(parent);
  }

  public static Builder builder(String title, Screen parent, ModConfigSpec spec) {
    return new Builder(title, parent, spec);
  }

  public static class Builder {
    private final String title;
    private final Screen parent;
    private final ModConfigSpec spec;
    private final List<ConfigTab> tabs = new ArrayList<>();

    private Builder(String title, Screen parent, ModConfigSpec spec) {
      this.title = title;
      this.parent = parent;
      this.spec = spec;
    }

    public Builder tab(String name, Consumer<ConfigTab> builder) {
      ConfigTab tab = new ConfigTab(name);
      builder.accept(tab);
      tabs.add(tab);
      return this;
    }

    public KwahsConfigScreen build() {
      return new KwahsConfigScreen(title, parent, spec, tabs);
    }
  }
}
