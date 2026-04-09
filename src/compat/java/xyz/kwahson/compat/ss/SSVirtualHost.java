package xyz.kwahson.compat.ss;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.p3pp3rf1y.sophisticatedcore.api.IStorageWrapper;
import net.p3pp3rf1y.sophisticatedcore.upgrades.ITickableUpgrade;
import net.p3pp3rf1y.sophisticatedstorage.block.ItemContentsStorage;

import javax.annotation.Nullable;
import java.util.List;
import java.util.function.Supplier;

/**
 * Virtual block-entity stand-in for a Sophisticated Storage shulker that lives in
 * an item slot, an accessory slot, an entity field, an item frame, or anywhere
 * else that isn't a placed block.
 *
 * <p>Two responsibilities:
 *
 * <ol>
 * <li><b>Tick {@link ITickableUpgrade}s.</b> Placed shulkers tick their upgrades
 * from {@code StorageBlockEntity.serverTick} with {@code (level, pos, null)}.
 * Item-form shulkers get nothing -- magnet, feeding, smelting, brewing, jukebox,
 * xp pump, tool-swap, etc. all sit idle. This class replicates the placed-block
 * tick path with caller-supplied {@code Level}/{@code BlockPos}/host-{@code Entity}
 * suppliers, and (unlike placed blocks) passes the host entity along so feeding/
 * tool-swap upgrades can target the wearer or rider.</li>
 *
 * <li><b>Persist mutations.</b> {@link StackStorageWrapper} is constructed with
 * three no-op save callbacks (verified by decompile of SS 1.5.31), so any
 * mutation to its contents -- including those produced by tickable upgrades --
 * never reaches {@link ItemContentsStorage#setDirty()}. Without that flag the
 * vanilla {@code DimensionDataStorage} skips the {@code SavedData} on world
 * save and the changes vanish on the next reload. We mark the storage dirty at
 * the end of any tick where at least one upgrade ran. The menu base
 * ({@link SSItemStorageMenu}) covers UI-driven mutations the same way.</li>
 * </ol>
 *
 * <p><b>Threading:</b> server-only. Construction is fine on any thread, but
 * {@link #tick()} and {@link #invalidate()} must run on the server thread --
 * {@link ItemContentsStorage#get()} returns the client copy off the server
 * thread.
 *
 * <p><b>Lifetime:</b> one host per source. Identity-cache the host alongside
 * whatever already tracks the source (entity field, slot index, etc.) and call
 * {@link #invalidate()} on swap, removal, dimension change, or whenever the
 * source identity changes. The host detects stale wrappers via stack identity
 * and rebuilds, but invalidating explicitly is cheaper and avoids one tick of
 * stale upgrade state.
 *
 * <p><b>Loading rule:</b> hard-links SS types. Never reference unless
 * {@link SSCompat#isLoaded()} returns {@code true}.
 *
 * <p><b>What this class deliberately doesn't do</b> -- see {@code TODO.md} in
 * the KwahsCore root for the deferred upgrade list (pickup, pump, battery,
 * blockconverter) and the rationale for each.
 */
public final class SSVirtualHost {
    private final Supplier<ItemStack> sourceSupplier;
    private final Supplier<Level> levelSupplier;
    private final Supplier<BlockPos> posSupplier;
    private final Supplier<Entity> contextEntitySupplier;

    // Rate-limit setDirty() to avoid forcing a full ItemContentsStorage
    // serialize on every autosave cycle. 100 ticks ≈ 5 seconds — worst-case
    // crash window for upgrade mutations.
    private static final int DIRTY_INTERVAL_TICKS = 100;
    private int ticksSinceDirty = 0;

    @Nullable
    private IStorageWrapper cachedWrapper;
    // Reference identity, not equality. ItemStack is mutable; the supplier may
    // return a different stack object after a swap, even if the contents look
    // the same. Identity catches "this is a fresh stack" cleanly without paying
    // for an NBT compare every tick.
    private ItemStack lastSourceRef = ItemStack.EMPTY;

    /**
     * @param sourceSupplier        the SS shulker stack. MUST return
     *                              {@link ItemStack#EMPTY} when the source is
     *                              gone (slot vacated, entity dead, etc.) --
     *                              never {@code null}.
     * @param levelSupplier         the level the host lives in. Used as the tick
     *                              context for upgrades.
     * @param posSupplier           the position upgrades should treat as "here".
     *                              For accessories pass the wearer's
     *                              {@code blockPosition()}; for boats pass the
     *                              entity's. Pump and blockconverter upgrades
     *                              read this to target nearby blocks -- see
     *                              TODO.md for the gotcha.
     * @param contextEntitySupplier the entity upgrades should treat as the
     *                              "user" -- the wearer for accessories, the
     *                              first player rider for boats. Placed blocks
     *                              pass {@code null} here; we pass an entity
     *                              when we have one so feeding, xp pump,
     *                              tool-swap and friends can target a real
     *                              player. May supply {@code null}.
     */
    public SSVirtualHost(Supplier<ItemStack> sourceSupplier,
                          Supplier<Level> levelSupplier,
                          Supplier<BlockPos> posSupplier,
                          Supplier<Entity> contextEntitySupplier) {
        this.sourceSupplier = sourceSupplier;
        this.levelSupplier = levelSupplier;
        this.posSupplier = posSupplier;
        this.contextEntitySupplier = contextEntitySupplier;
    }

    /**
     * Run one tick of upgrade processing. Safe to call every server tick.
     *
     * <p>Cost profile: a passive shulker (no tickable upgrades) pays the
     * wrapper rebuild cost on first tick and on every identity change of the
     * source stack, then early-returns on the empty tickable list. A shulker
     * with tickable upgrades pays the upgrade-list lookup plus each upgrade's
     * own tick body. {@code setDirty()} is rate-limited to once per
     * {@value #DIRTY_INTERVAL_TICKS} ticks per host to avoid forcing the
     * whole {@link ItemContentsStorage} to serialize on every autosave cycle.
     * Flushed eagerly on {@link #invalidate()} so session boundaries (logout,
     * respawn, dimension change, shulker swap) don't lose pending mutations.
     *
     * <p><b>Server-side only.</b> The class enforces this defensively at the
     * top of the method by checking the level type. Calling on the client
     * silently early-returns without touching {@link ItemContentsStorage} (its
     * {@code get()} returns the client copy off the server thread group, so
     * mutations would corrupt the dirty-flag accounting).
     */
    public void tick() {
        Level level = levelSupplier.get();
        // Defensive: enforce the server-side contract instead of trusting
        // every caller. ItemContentsStorage.get() returns the client copy off
        // the server thread group; without this guard, a misrouted caller
        // would silently mutate client-side state.
        if (!(level instanceof ServerLevel)) return;

        ItemStack source = sourceSupplier.get();
        if (source.isEmpty()) {
            // Source gone -- drop the wrapper. Next tick with a real source
            // will rebuild from scratch.
            if (cachedWrapper != null) {
                invalidate();
            }
            return;
        }

        // Stack identity changed -- swap, reload from disk, etc. Rebuild.
        // Note: SS's StorageWrapperRepository keys wrappers by ItemStack
        // identity, so the rebuilt wrapper for the same stack is actually the
        // same instance the rest of SS will see. No risk of duelling wrappers.
        if (source != lastSourceRef) {
            cachedWrapper = SSPersistence.createWrapper(level.registryAccess(), source);
            lastSourceRef = source;
        }

        if (cachedWrapper == null) return;

        List<ITickableUpgrade> tickables =
                cachedWrapper.getUpgradeHandler().getWrappersThatImplement(ITickableUpgrade.class);
        if (tickables.isEmpty()) return;

        BlockPos pos = posSupplier.get();
        if (pos == null) return;
        Entity contextEntity = contextEntitySupplier.get();

        for (ITickableUpgrade tickable : tickables) {
            tickable.tick(contextEntity, level, pos);
        }

        // Rate-limit the dirty flag. Calling setDirty() every tick forces a
        // full ItemContentsStorage serialize on every autosave -- same cost as
        // placed-block SS shulkers, but multiplied by every equipped/mounted
        // shulker with any tickable upgrade across all players. Batching to
        // once per DIRTY_INTERVAL_TICKS (≈5s) drops autosave I/O dramatically
        // with a bounded crash-loss window. Flushed eagerly in invalidate()
        // so logout/respawn/swap never lose pending mutations.
        if (++ticksSinceDirty >= DIRTY_INTERVAL_TICKS) {
            ticksSinceDirty = 0;
            SSPersistence.flushUpgradeState(cachedWrapper);
        }
    }

    /**
     * Drop the cached wrapper. Call when the source identity is about to
     * change in a way the supplier can't observe -- shulker swap on a boat,
     * accessory slot reassign, dimension change, entity teleport across worlds.
     * Flushes any pending dirty state so mutations since the last rate-limited
     * {@code setDirty()} aren't lost. Cheap; safe to call when nothing is cached.
     */
    public void invalidate() {
        if (cachedWrapper != null && ticksSinceDirty > 0) {
            SSPersistence.saveWrapperState(cachedWrapper);
        }
        ticksSinceDirty = 0;
        cachedWrapper = null;
        lastSourceRef = ItemStack.EMPTY;
    }
}
