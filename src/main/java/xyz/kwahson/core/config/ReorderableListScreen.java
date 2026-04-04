package xyz.kwahson.core.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * A screen with a drag-and-drop reorderable list. Each entry has a toggle
 * checkbox and an optional settings button.
 *
 * <p>Used for configurable ordered lists like HUD overlay lines. The list
 * preserves order for both enabled and disabled items.
 *
 * <p>Usage:
 * <pre>{@code
 * ReorderableListScreen.builder("Info Overlay", parent)
 *     .entry("coords", "Coordinates", screen -> openCoordsSettings(screen))
 *     .entry("biome", "Biome")
 *     .savedOrder(config.ORDER.get())
 *     .savedEnabled(config.ENABLED.get())
 *     .onSave((order, enabled) -> { config.ORDER.set(order); })
 *     .build();
 * }</pre>
 */
public class ReorderableListScreen extends Screen {

  private final Screen parent;
  private final List<EntryData> entries;
  private final BiConsumer<List<String>, Set<String>> onSave;
  private ReorderableListWidget listWidget;

  private ReorderableListScreen(String title, Screen parent,
                                List<EntryData> entries,
                                BiConsumer<List<String>, Set<String>> onSave) {
    super(Component.literal(title));
    this.parent = parent;
    this.entries = entries;
    this.onSave = onSave;
  }

  @Override
  protected void init() {
    this.listWidget = new ReorderableListWidget(this.minecraft, this.width,
        this.height - 64, 32, 24);

    for (EntryData data : entries) {
      this.listWidget.addItem(new ReorderableListWidget.ReorderableEntry(
          data.id, data.label, data.enabled, data.onSettings));
    }

    this.addRenderableWidget(this.listWidget);
    this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, btn -> onClose())
        .bounds(this.width / 2 - 100, this.height - 28, 200, 20).build());
  }

  @Override
  public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    super.render(graphics, mouseX, mouseY, partialTick);
    graphics.drawCenteredString(this.font, this.title, this.width / 2, 12, 0xFFFFFF);
  }

  @Override
  public void onClose() {
    // Collect current order and enabled state
    List<String> order = new ArrayList<>();
    Set<String> enabled = new HashSet<>();
    for (int i = 0; i < listWidget.children().size(); i++) {
      var entry = listWidget.children().get(i);
      order.add(entry.getId());
      if (entry.isEnabled()) {
        enabled.add(entry.getId());
      }
    }
    onSave.accept(order, enabled);
    this.minecraft.setScreen(parent);
  }

  public static Builder builder(String title, Screen parent) {
    return new Builder(title, parent);
  }

  // -- Builder --

  public static class Builder {
    private final String title;
    private final Screen parent;
    private final Map<String, EntryData> entriesById = new LinkedHashMap<>();
    private List<String> savedOrder;
    private Set<String> savedEnabled;
    private BiConsumer<List<String>, Set<String>> onSave = (o, e) -> {};

    private Builder(String title, Screen parent) {
      this.title = title;
      this.parent = parent;
    }

    /** Adds an entry with no settings button. */
    public Builder entry(String id, String label) {
      entriesById.put(id, new EntryData(id, label, false, null));
      return this;
    }

    /** Adds an entry with a settings button. */
    public Builder entry(String id, String label, Consumer<Screen> onSettings) {
      entriesById.put(id, new EntryData(id, label, false, onSettings));
      return this;
    }

    /** Sets the saved order. Items not in the entry list are ignored. New entries append at end. */
    public Builder savedOrder(List<String> order) {
      this.savedOrder = order;
      return this;
    }

    /** Sets which entries are enabled. */
    public Builder savedEnabled(List<String> enabled) {
      this.savedEnabled = new HashSet<>(enabled);
      return this;
    }

    /** Called with (orderedIds, enabledIds) when Done is pressed. */
    public Builder onSave(BiConsumer<List<String>, Set<String>> callback) {
      this.onSave = callback;
      return this;
    }

    public ReorderableListScreen build() {
      List<EntryData> ordered = new ArrayList<>();

      // Apply saved order: known IDs first in saved order, then new entries at end
      if (savedOrder != null) {
        for (String id : savedOrder) {
          EntryData data = entriesById.remove(id);
          if (data != null) ordered.add(data);
        }
      }
      // Remaining entries (new or no saved order) in definition order
      ordered.addAll(entriesById.values());

      // Apply enabled state
      if (savedEnabled != null) {
        for (EntryData data : ordered) {
          data.enabled = savedEnabled.contains(data.id);
        }
      }

      return new ReorderableListScreen(title, parent, ordered, onSave);
    }
  }

  // -- Data --

  private static class EntryData {
    final String id;
    final String label;
    boolean enabled;
    final Consumer<Screen> onSettings;

    EntryData(String id, String label, boolean enabled, Consumer<Screen> onSettings) {
      this.id = id;
      this.label = label;
      this.enabled = enabled;
      this.onSettings = onSettings;
    }
  }

  // -- List Widget --

  static class ReorderableListWidget
      extends ObjectSelectionList<ReorderableListWidget.ReorderableEntry> {

    private int draggedIndex = -1;

    public ReorderableListWidget(Minecraft mc, int width, int height, int y, int itemHeight) {
      super(mc, width, height, y, itemHeight);
    }

    public void addItem(ReorderableEntry entry) {
      this.addEntry(entry);
    }

    @Override
    public int getRowWidth() {
      return 260;
    }

    // Drag-and-drop: on click in handle area, start drag
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
      if (button == 0) {
        ReorderableEntry entry = this.getEntryAtPosition(mouseX, mouseY);
        if (entry != null) {
          int index = this.children().indexOf(entry);
          int left = this.getRowLeft();

          // Handle area: first 14px of the row
          if (mouseX >= left && mouseX < left + 14) {
            this.draggedIndex = index;
            return true;
          }

          // Toggle area: 14-34px
          if (mouseX >= left + 14 && mouseX < left + 34) {
            entry.toggleEnabled();
            return true;
          }

          // Settings button: last 20px (if present)
          if (entry.hasSettings() && mouseX >= this.getRowRight() - 20) {
            entry.openSettings(this.minecraft.screen);
            return true;
          }
        }
      }
      return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button,
                                double dragX, double dragY) {
      if (draggedIndex >= 0 && button == 0) {
        // Calculate which index the mouse is over
        int hoverIndex = getIndexAtY(mouseY);
        if (hoverIndex >= 0 && hoverIndex < this.children().size()
            && hoverIndex != draggedIndex) {
          Collections.swap(this.children(), draggedIndex, hoverIndex);
          draggedIndex = hoverIndex;
        }
        return true;
      }
      return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
      if (draggedIndex >= 0) {
        draggedIndex = -1;
        return true;
      }
      return super.mouseReleased(mouseX, mouseY, button);
    }

    private int getIndexAtY(double mouseY) {
      int relY = (int)(mouseY - this.getY()) - 4 + (int)this.getScrollAmount();
      int raw = relY / this.itemHeight;
      return Math.max(0, Math.min(this.children().size() - 1, raw));
    }

    boolean isDragging(int index) {
      return index == draggedIndex;
    }

    // -- Entry --

    static class ReorderableEntry extends ObjectSelectionList.Entry<ReorderableEntry> {

      private static final int HANDLE_COLOR = 0xFF888888;
      private static final int HANDLE_DRAG_COLOR = 0xFFCCCCCC;
      private static final int CHECK_COLOR = 0xFF55FF55;
      private static final int BOX_COLOR = 0xFFAAAAAA;
      private static final int SETTINGS_COLOR = 0xFFBBBBBB;
      private static final int SETTINGS_HOVER_COLOR = 0xFFFFFFFF;
      private static final int LABEL_COLOR = 0xFFFFFFFF;
      private static final int LABEL_DISABLED_COLOR = 0xFF888888;
      private static final int DRAG_BG_COLOR = 0x40FFFFFF;

      private final String id;
      private final String label;
      private boolean enabled;
      private final Consumer<Screen> onSettings;

      ReorderableEntry(String id, String label, boolean enabled,
                       Consumer<Screen> onSettings) {
        this.id = id;
        this.label = label;
        this.enabled = enabled;
        this.onSettings = onSettings;
      }

      String getId() { return id; }
      boolean isEnabled() { return enabled; }
      boolean hasSettings() { return onSettings != null; }

      void toggleEnabled() { enabled = !enabled; }

      void openSettings(Screen currentScreen) {
        if (onSettings != null) onSettings.accept(currentScreen);
      }

      @Override
      public Component getNarration() {
        return Component.literal(label);
      }

      @Override
      public void render(GuiGraphics graphics, int index, int top, int left,
                         int width, int height, int mouseX, int mouseY,
                         boolean hovering, float partialTick) {

        Font font = Minecraft.getInstance().font;
        ReorderableListWidget listWidget = (ReorderableListWidget) this.list;
        boolean dragging = listWidget.isDragging(index);

        // Drag highlight background
        if (dragging) {
          graphics.fill(left, top, left + width, top + height, DRAG_BG_COLOR);
        }

        int y = top + (height - 9) / 2;

        // Drag handle: 3 horizontal lines
        int handleColor = dragging ? HANDLE_DRAG_COLOR : HANDLE_COLOR;
        for (int i = 0; i < 3; i++) {
          int lineY = top + 5 + i * 4;
          graphics.fill(left + 2, lineY, left + 10, lineY + 1, handleColor);
        }

        // Checkbox: 10x10 box with optional checkmark
        int boxX = left + 16;
        int boxY = top + (height - 10) / 2;
        graphics.fill(boxX, boxY, boxX + 10, boxY + 10, BOX_COLOR);
        graphics.fill(boxX + 1, boxY + 1, boxX + 9, boxY + 9, 0xFF000000);
        if (enabled) {
          graphics.fill(boxX + 2, boxY + 2, boxX + 8, boxY + 8, CHECK_COLOR);
        }

        // Label
        int labelX = left + 32;
        int labelColor = enabled ? LABEL_COLOR : LABEL_DISABLED_COLOR;
        graphics.drawString(font, label, labelX, y, labelColor);

        // Settings gear button
        if (hasSettings()) {
          int gearX = left + width - 18;
          int gearY = top + (height - 9) / 2;
          boolean gearHover = mouseX >= gearX - 2 && mouseX <= gearX + 12
              && mouseY >= top && mouseY <= top + height;
          int gearColor = gearHover ? SETTINGS_HOVER_COLOR : SETTINGS_COLOR;
          graphics.drawString(font, "\u2699", gearX, gearY, gearColor);
        }
      }
    }
  }
}
