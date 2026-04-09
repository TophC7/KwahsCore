package xyz.kwahson.compat.ss;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.p3pp3rf1y.sophisticatedcore.api.IStorageWrapper;
import net.p3pp3rf1y.sophisticatedcore.init.ModCoreDataComponents;
import net.p3pp3rf1y.sophisticatedstorage.block.ItemContentsStorage;
import net.p3pp3rf1y.sophisticatedstorage.block.StorageBlockEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.p3pp3rf1y.sophisticatedstorage.block.StorageWrapper;
import net.p3pp3rf1y.sophisticatedstorage.item.StackStorageWrapper;

import javax.annotation.Nullable;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence utilities for Sophisticated Storage shulkers in item form.
 *
 * <p>Two problems this class solves:
 *
 * <ol>
 * <li><b>Settings orphan.</b> {@code StorageWrapper.loadData()} reads
 * {@code tag.getCompound("settings")}. If the key is absent, NBT returns a
 * new orphan {@link CompoundTag} that is not a child of the wrapper tag. The
 * {@code SettingsHandler} captures that orphan -- all mutations go into it,
 * but they never reach {@link ItemContentsStorage} because the orphan is not
 * in the tag tree. {@link #ensureStorageCompoundsExist} pre-seeds the key so
 * the orphan never forms.</li>
 *
 * <li><b>Upgrade config loss.</b> {@code UpgradeHandler} holds in-memory
 * {@link ItemStack}s for each installed upgrade. When a user edits an
 * upgrade's config (filter allowlists, feeding settings, etc.), the mutation
 * lands on the in-memory stack's data components, but
 * {@code saveInventory()} is never called because the no-op save handler on
 * {@link StackStorageWrapper} never triggers it. {@link #saveWrapperState}
 * forces serialization on session boundaries.</li>
 * </ol>
 *
 * <p><b>Loading rule:</b> hard-links SS types. Never reference unless
 * {@link SSCompat#isLoaded()} returns {@code true}.
 */
public final class SSPersistence {

    private static final Logger LOGGER = LoggerFactory.getLogger("KwahsCore/SSPersistence");

    // No public constant on StorageWrapper for these keys.
    private static final String OPEN_TAB_ID_TAG = "openTabId";
    private static final String COLUMNS_TAKEN_TAG = "columnsTaken";

    private SSPersistence() {}

    // PRE-SEED //

    /**
     * Pre-seed {@code "contents"}, {@code "settings"}, and {@code "renderInfo"}
     * compounds as live children in the storage wrapper tag. Call before every
     * {@link StackStorageWrapper#fromStack} invocation.
     *
     * <p>When these keys exist as children of the wrapper tag,
     * {@code StorageWrapper.loadData()} gets live references from
     * {@link CompoundTag#getCompound}. Mutations by the {@code SettingsHandler}
     * flow into the tag tree and reach {@link ItemContentsStorage} on the next
     * {@code setDirty()}. Without pre-seeding, {@code getCompound()} returns
     * orphan tags and mutations are silently lost.
     *
     * <p>No-op for stacks without a UUID (never-opened shulkers) and for
     * stacks whose wrapper tag already contains the keys (existing data is
     * never clobbered).
     */
    public static void ensureStorageCompoundsExist(ItemStack stack) {
        UUID uuid = getStorageUuid(stack);
        if (uuid == null) return;

        CompoundTag rootTag = ItemContentsStorage.get().getOrCreateStorageContents(uuid);

        if (!rootTag.contains(StorageBlockEntity.STORAGE_WRAPPER_TAG, Tag.TAG_COMPOUND)) {
            rootTag.put(StorageBlockEntity.STORAGE_WRAPPER_TAG, new CompoundTag());
        }
        CompoundTag wrapperTag = rootTag.getCompound(StorageBlockEntity.STORAGE_WRAPPER_TAG);

        // Pre-seed sub-compounds to prevent orphan tags (see class Javadoc).
        if (!wrapperTag.contains(StorageWrapper.CONTENTS_TAG, Tag.TAG_COMPOUND)) {
            wrapperTag.put(StorageWrapper.CONTENTS_TAG, new CompoundTag());
        }
        if (!wrapperTag.contains(IStorageWrapper.SETTINGS_TAG, Tag.TAG_COMPOUND)) {
            wrapperTag.put(IStorageWrapper.SETTINGS_TAG, new CompoundTag());
        }
        if (!wrapperTag.contains(StorageWrapper.RENDER_INFO_TAG, Tag.TAG_COMPOUND)) {
            wrapperTag.put(StorageWrapper.RENDER_INFO_TAG, new CompoundTag());
        }
    }

    /**
     * Atomically pre-seed and create a wrapper. Drop-in replacement for
     * {@link StackStorageWrapper#fromStack} that ensures contents, settings,
     * and render-info compounds are live before the wrapper loads.
     */
    public static IStorageWrapper createWrapper(HolderLookup.Provider registries, ItemStack stack) {
        ensureStorageCompoundsExist(stack);
        return StackStorageWrapper.fromStack(registries, stack);
    }

    // SAVE-BACK //

    /**
     * Lightweight flush: serialize upgrade stacks and mark dirty. Suitable for
     * the rate-limited tick path where full scalar/settings serialization is
     * unnecessary -- tick-driven mutations only touch upgrade in-memory stacks.
     *
     * <p>No-op if the wrapper has no UUID or no upgrades are installed.
     */
    public static void flushUpgradeState(IStorageWrapper wrapper) {
        if (wrapper.getUpgradeHandler().getSlots() > 0) {
            wrapper.getUpgradeHandler().saveInventory();
        }
        wrapper.getContentsUuid().ifPresent(uuid -> {
            ItemContentsStorage.get().setDirty();
        });
    }

    /**
     * Full save: serialize all in-flight wrapper state to
     * {@link ItemContentsStorage} and mark dirty. Call on session boundaries
     * only (menu close, host invalidation) -- not on the tick path.
     *
     * <p>What this persists beyond {@link #flushUpgradeState}:
     * <ul>
     * <li>Settings handler NBT (orphan copy-back)</li>
     * <li>Sort preference ({@code sortBy}), open tab ID, columns taken</li>
     * <li>Colors (main/accent)</li>
     * </ul>
     *
     * <p>No-op if the wrapper has no UUID (never-opened shulker) or is a
     * {@code NoopStorageWrapper}.
     */
    public static void saveWrapperState(IStorageWrapper wrapper) {
        LOGGER.debug("[saveState] wrapper type={}, uuid={}",
                wrapper.getClass().getSimpleName(), wrapper.getContentsUuid().orElse(null));

        if (wrapper.getUpgradeHandler().getSlots() > 0) {
            wrapper.getUpgradeHandler().saveInventory();
        }

        wrapper.getContentsUuid().ifPresent(uuid -> {
            ItemContentsStorage storage = ItemContentsStorage.get();
            CompoundTag rootTag = storage.getOrCreateStorageContents(uuid);
            CompoundTag wrapperTag = rootTag.getCompound(StorageBlockEntity.STORAGE_WRAPPER_TAG);

            // Settings: the SettingsHandler captures its own CompoundTag at
            // construction time. Categories save to THAT tag, not the live
            // child in the wrapperTag. Copy it into the tag tree.
            CompoundTag settingsFromHandler = wrapper.getSettingsHandler().getNbt();
            if (!settingsFromHandler.isEmpty()) {
                wrapperTag.put(IStorageWrapper.SETTINGS_TAG, settingsFromHandler);
            }

            // Scalar fields that StorageWrapper only serializes via the
            // package-private saveData(). Write them using public getters.
            wrapperTag.putString(StorageWrapper.SORT_BY_TAG,
                    wrapper.getSortBy().getSerializedName());

            Optional<Integer> openTabId = wrapper.getOpenTabId();
            if (openTabId.isPresent()) {
                wrapperTag.putInt(OPEN_TAB_ID_TAG, openTabId.get());
            } else {
                wrapperTag.remove(OPEN_TAB_ID_TAG);
            }

            int columnsTaken = wrapper.getColumnsTaken();
            if (columnsTaken > 0) {
                wrapperTag.putInt(COLUMNS_TAKEN_TAG, columnsTaken);
            }

            if (wrapper instanceof StorageWrapper sw) {
                int mainColor = sw.getMainColor();
                int accentColor = sw.getAccentColor();
                if (mainColor != -1) {
                    wrapperTag.putInt(StorageWrapper.MAIN_COLOR_TAG, mainColor);
                } else {
                    wrapperTag.remove(StorageWrapper.MAIN_COLOR_TAG);
                }
                if (accentColor != -1) {
                    wrapperTag.putInt(StorageWrapper.ACCENT_COLOR_TAG, accentColor);
                } else {
                    wrapperTag.remove(StorageWrapper.ACCENT_COLOR_TAG);
                }
            }

            storage.setDirty();
        });
    }

    // HELPERS //

    @Nullable
    private static UUID getStorageUuid(ItemStack stack) {
        return stack.get(ModCoreDataComponents.STORAGE_UUID.get());
    }
}
