package xyz.kwahson.compat.ss;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.p3pp3rf1y.sophisticatedcore.api.IStorageWrapper;
import net.p3pp3rf1y.sophisticatedcore.common.gui.StorageContainerMenuBase;
import net.p3pp3rf1y.sophisticatedcore.upgrades.UpgradeHandler;
import net.p3pp3rf1y.sophisticatedcore.util.NoopStorageWrapper;

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
 * TODO: persistence hook for sort/inventory changes. SS's StackStorageWrapper stores
 * contents in ItemContentsStorage (a SavedData) keyed by UUID. Direct mutations to the
 * wrapper's contents tag bypass SavedData.setDirty(), so sort and slot edits may not
 * survive a level save. The fix likely lives in a server-side mutation hook that calls
 * {@code ItemContentsStorage.get().setDirty()} after each menu mutation. Verify the
 * exact failure mode with a debugger before implementing.
 */
public abstract class SSItemStorageMenu extends StorageContainerMenuBase<IStorageWrapper> {
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
     * Settings UI requires a BlockPos. No-op for item-based storage.
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
}
