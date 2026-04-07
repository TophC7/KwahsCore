package xyz.kwahson.core.compat.ss;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Detection layer for Sophisticated Storage shulker boxes.
 * <p>
 * This class is safe to load whether SS is present or not. It only references
 * SS classes through {@link Class#forName(String)}, so the JVM never link-resolves
 * any SS type via this file.
 * <p>
 * Other classes in this package ({@link SSItemStorageMenu}, {@link SSItemStorageScreen})
 * <strong>do</strong> hard-link SS types. Callers must gate every reference to those
 * classes behind {@link #isLoaded()}, otherwise classloading will fail when SS is absent.
 */
public final class SSCompat {
    private static final Logger LOGGER = LoggerFactory.getLogger("KwahsCore/SSCompat");
    private static final String SS_MOD_ID = "sophisticatedstorage";
    private static final String SHULKER_BLOCK_CLASS = "net.p3pp3rf1y.sophisticatedstorage.block.ShulkerBoxBlock";
    // volatile for safe publication: init() runs on the mod-construction thread but
    // isSSShulkerBox/findAllShulkerItems are called from render, server, and network threads.
    // Without volatile the JMM permits a reader to observe loaded=true while still seeing
    // shulkerBlockClass=null, which would NPE inside isInstance(...).
    private static volatile boolean loaded = false;
    private static volatile Class<?> shulkerBlockClass;
    private static volatile List<Item> cachedShulkerItems;
    // single-threaded init guard, only touched from init() itself
    private static boolean initAttempted = false;

    private SSCompat() {}

    /**
     * Probe for Sophisticated Storage. Idempotent. Safe to call from multiple
     * mod constructors. Pure detection, no registration.
     */
    public static void init() {
        if (initAttempted)
            return;
        initAttempted = true;
        if (!ModList.get().isLoaded(SS_MOD_ID))
            return;
        try {
            shulkerBlockClass = Class.forName(SHULKER_BLOCK_CLASS);
            loaded = true;
            LOGGER.info("Sophisticated Storage compat enabled");
        } catch (ClassNotFoundException e) {
            LOGGER.warn("Sophisticated Storage is loaded but {} was not found. SS may have changed its internal class names.", SHULKER_BLOCK_CLASS);
        }
    }

    public static boolean isLoaded() { return loaded; }

    /**
     * Identity check. Fast. Safe to call from per-frame paths.
     * Returns false if SS is not loaded or the stack is empty.
     */
    public static boolean isSSShulkerBox(ItemStack stack) {
        if (!loaded || stack.isEmpty())
            return false;
        return shulkerBlockClass.isInstance(Block.byItem(stack.getItem()));
    }

    /**
     * Walk the item registry and collect every item whose backing block is an
     * SS shulker. Cached after first call: the registry is frozen by the time
     * any consumer calls this, so the result is constant for the lifetime of the JVM.
     * <p>
     * Use this at setup time (e.g. {@code FMLCommonSetupEvent}) to enumerate
     * variants for accessory/slot/renderer registration. Replaces hard-coded ID
     * lists, so new SS tiers are picked up automatically without a core update.
     */
    public static List<Item> findAllShulkerItems() {
        if (!loaded)
            return List.of();
        List<Item> cached = cachedShulkerItems;
        if (cached != null)
            return cached;
        List<Item> out = new ArrayList<>();
        for (Item item : BuiltInRegistries.ITEM) {
            if (shulkerBlockClass.isInstance(Block.byItem(item))) {
                out.add(item);
            }
        }
        cached = List.copyOf(out);
        cachedShulkerItems = cached;
        return cached;
    }
}
