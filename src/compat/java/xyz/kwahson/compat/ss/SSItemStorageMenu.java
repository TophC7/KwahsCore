package xyz.kwahson.compat.ss;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.p3pp3rf1y.sophisticatedcore.api.IStorageWrapper;
import net.p3pp3rf1y.sophisticatedcore.common.gui.ISyncedContainer;
import net.p3pp3rf1y.sophisticatedcore.common.gui.StorageContainerMenuBase;
import net.p3pp3rf1y.sophisticatedcore.upgrades.UpgradeHandler;
import net.p3pp3rf1y.sophisticatedcore.util.NoopStorageWrapper;
import net.p3pp3rf1y.sophisticatedstorage.block.ItemContentsStorage;
import net.p3pp3rf1y.sophisticatedstorage.item.StackStorageWrapper;

import javax.annotation.Nullable;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Abstract menu base for Sophisticated Storage shulker boxes that aren't placed
 * as blocks. The shulker can live in a player slot, an accessory slot, an entity,
 * an item frame, or anywhere else; subclasses just provide a {@code Supplier<ItemStack>}
 * for "where do I read the source from."
 * <p>
 * Subclasses must:
 * <ul>
 * <li>Register their own {@link MenuType} (per-mod, with their own network codec)</li>
 * <li>Pass a source supplier and an optional opened-stack reference for tamper detection</li>
 * <li>Override {@link #storageItemHasChanged()} if identity check on the source isn't right</li>
 * <li>Override {@link #stillValid(Player)} if extra checks are needed (distance, capability, etc.)</li>
 * <li>Optionally override {@link #getEntity()} to expose a host entity for upgrade context</li>
 * </ul>
 * <p>
 * <strong>API asymmetry:</strong> {@link #getBlockPosition()} is {@code final}
 * (faking a position breaks SS's neighbor and chunk lookups in subtle ways), while
 * {@link #getEntity()} is overridable (entity context is optional and benign).
 * <p>
 * <strong>Supplier contract:</strong> the supplier MUST return {@link ItemStack#EMPTY}
 * when the source is unreachable (slot out of bounds, capability missing, entity dead).
 * Returning {@code null} will NPE the validity checks.
 * <p>
 * <strong>Loading rule:</strong> this class hard-links SS types. Never reference it
 * unless {@link SSCompat#isLoaded()} returns true.
 * <p>
 * <strong>Persistence:</strong> {@link StackStorageWrapper} is built with three
 * no-op save callbacks (verified by SS 1.5.31 decompile), so any mutation to its
 * contents -- sort, slot edits, settings, upgrade installs -- never reaches
 * {@link ItemContentsStorage#setDirty()}. Without that flag, vanilla's
 * {@code DimensionDataStorage} skips the {@code SavedData} on save and the
 * changes vanish on next world reload. The wrapper's in-memory {@code CompoundTag}
 * is the same reference held by {@link ItemContentsStorage}, so we don't need
 * to copy anything back -- only the dirty flag is missing. We override
 * {@link #removed(Player)} to mark the storage dirty once when the menu closes
 * server-side. Tick-driven mutations from {@code SSVirtualHost} are handled
 * the same way at the end of any tick where upgrades ran.
 */
public abstract class SSItemStorageMenu extends StorageContainerMenuBase<IStorageWrapper> implements ISyncedContainer {
    private final Supplier<ItemStack> sourceSupplier;
    // exact ItemStack reference we opened the menu with. Used as a tamper detector
    // on save/close paths. Null when subclass tracks identity differently (e.g. boats).
    @Nullable
    private final ItemStack openedRef;

    protected SSItemStorageMenu(MenuType<?> menuType,
            int containerId,
            Player player,
            IStorageWrapper wrapper,
            Supplier<ItemStack> sourceSupplier,
            @Nullable ItemStack openedRef) {
        super(menuType, containerId, player, wrapper, NoopStorageWrapper.INSTANCE, -1, false);
        this.sourceSupplier = sourceSupplier;
        this.openedRef = openedRef;
    }
    // SOURCE ACCESS //

    /** Current source stack. May change between calls (slot swap, entity field update). */
    public final ItemStack getSourceStack() { return sourceSupplier.get(); }

    /** Reference to the stack we opened with, if tracked. */
    @Nullable
    public final ItemStack getOpenedRef() { return openedRef; }
    // SS HOOKS //

    /**
     * Item-based storage has no BlockPos by definition. Final because subclasses
     * trying to fake one will break SS upgrade logic in subtle ways.
     */
    @Override
    public final Optional<BlockPos> getBlockPosition() { return Optional.empty(); }

    /**
     * Optional host entity. Default empty. Boat-style consumers override this so
     * SS upgrades that need an entity context (feeding, pickup) can target it.
     */
    @Override
    public Optional<Entity> getEntity() { return Optional.empty(); }

    @Override
    protected StorageUpgradeSlot instantiateUpgradeSlot(UpgradeHandler handler, int slot) { return new StorageUpgradeSlot(handler, slot); }

    /**
     * No-op for now. A proper item-form settings menu requires a custom
     * SettingsContainerMenu subclass + screen + MenuType registration per
     * consuming mod. Deferred -- see TODO.md.
     */
    @Override
    public void openSettings() {}

    @Override
    public boolean detectSettingsChangeAndReload() { return false; }

    /**
     * Default identity check. Subclasses with non-identity source semantics
     * (e.g. an entity field that gets reassigned on swap) should override.
     */
    @Override
    protected boolean storageItemHasChanged() {
        if (openedRef == null)
            return false;
        return sourceSupplier.get() != openedRef;
    }

    /**
     * Default validity: source stack still exists, is still an SS shulker, and
     * (when tracking identity) still matches the opened reference. Subclasses
     * with extra constraints (distance, capability lookup) override and may
     * call super.
     */
    @Override
    public boolean stillValid(Player player) {
        ItemStack current = sourceSupplier.get();
        if (current.isEmpty())
            return false;
        if (!SSCompat.isSSShulkerBox(current))
            return false;
        if (openedRef != null && current != openedRef)
            return false;
        return true;
    }

    /**
     * Persistence hook for menu-driven mutations.
     *
     * <p>SS routes every UI action -- sort, slot edit, settings change, upgrade
     * install, color change -- through this menu. The wrapper's in-memory
     * {@code CompoundTag} is the same reference {@link ItemContentsStorage}
     * holds in its {@code storageContents} map, so any mutation made through
     * the menu is already visible to the {@code SavedData} the moment it
     * lands; the only thing missing is the dirty flag that tells
     * {@code DimensionDataStorage} to write the {@code SavedData} on the next
     * autosave.
     *
     * <p>We mark dirty once here on close, instead of every tick the menu is
     * open, because the cost of the dirty flag is <em>not</em> a boolean
     * assignment -- when {@code DimensionDataStorage} sees a dirty
     * {@code SavedData} it serializes the entire {@code ItemContentsStorage}
     * (every UUID -> contents pair across the whole world). Doing that on
     * every autosave that touched a menu would cost the server I/O
     * proportional to total-SS-inventory rather than actually-changed
     * shulkers. Marking dirty once at close captures the full session in a
     * single autosave write.
     *
     * <p><b>Crash window:</b> if the server crashes while the menu is still
     * open, mutations made during the session are lost. Acceptable tradeoff
     * for the I/O savings; users running mission-critical setups should close
     * their menus periodically.
     */
    @Override
    public void removed(Player playerArg) {
        super.removed(playerArg);
        if (playerArg instanceof ServerPlayer) {
            SSPersistence.saveWrapperState(getStorageWrapper());
        }
    }
}
