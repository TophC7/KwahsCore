package xyz.kwahson.compat.ss;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.p3pp3rf1y.sophisticatedcore.client.gui.StorageScreenBase;

/**
 * Abstract screen base for item-based SS shulker menus. Extends SS's screen base
 * so consumers get the full upgrade/sort/settings UI for free, and centralizes
 * the {@code getStorageSettingsTabTooltip} boilerplate.
 * <p>
 * Subclasses can override {@code init}, {@code render}, and {@code mouseClicked}
 * to layer their own overlays (e.g. a tab switcher between equipped accessory shulkers).
 * <p>
 * <strong>Loading rule:</strong> this class hard-links SS types and is client-only.
 * Never reference it on the dedicated server, and never reference it without first
 * checking {@link SSCompat#isLoaded()}.
 */
public abstract class SSItemStorageScreen<M extends SSItemStorageMenu> extends StorageScreenBase<M> {
    protected SSItemStorageScreen(M menu, Inventory playerInv, Component title) { super(menu, playerInv, title); }

    @Override
    protected String getStorageSettingsTabTooltip() { return "gui.sophisticatedstorage.settings.tooltip"; }
}
