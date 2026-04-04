package xyz.kwahson.core.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Safe wrappers around {@link ModConfigSpec.ConfigValue#get()} that never
 * crash on stale or corrupted config files. Returns the fallback value
 * and logs a warning once per key (not every tick).
 *
 * <p>Also provides {@link #validateOrReset(String, ModConfigSpec, ModConfigSpec.ConfigValue[])}
 * to detect corrupted config files at startup and replace them with fresh defaults.
 */
public final class SafeConfig {

  private static final Logger LOG = LoggerFactory.getLogger("KwahsCore");
  private static final Set<String> WARNED = new HashSet<>();

  private SafeConfig() {}

  /**
   * Validates config by trying to read the given sentinel values. If any
   * throw a type error, the config file is renamed to {@code .corrupted}
   * and the game continues with in-memory defaults.
   *
   * <p>Call this once in the mod constructor, after {@code registerConfig()}.
   * Pass a few representative values that cover different types in your spec.
   *
   * @param modId     the mod ID (used to find the config file name)
   * @param spec      the config spec
   * @param sentinels values to test-read for type correctness
   */
  public static void validateOrReset(String modId, ModConfigSpec spec,
                                     ModConfigSpec.ConfigValue<?>... sentinels) {
    if (!spec.isLoaded()) return;

    boolean corrupted = false;
    for (ModConfigSpec.ConfigValue<?> sentinel : sentinels) {
      try {
        sentinel.get();
      } catch (ClassCastException | NullPointerException e) {
        corrupted = true;
        break;
      }
    }

    if (!corrupted) return;

    Path configDir = FMLPaths.CONFIGDIR.get();
    Path configFile = configDir.resolve(modId + "-client.toml");

    if (Files.exists(configFile)) {
      Path backup = configDir.resolve(modId + "-client.toml.corrupted");
      try {
        Files.deleteIfExists(backup);
        Files.move(configFile, backup);
        LOG.warn("[{}] Config file was corrupted or outdated. Moved to {} and using defaults. "
            + "Settings will regenerate on next launch.", modId, backup.getFileName());
      } catch (IOException e) {
        LOG.error("[{}] Failed to backup corrupted config: {}", modId, e.getMessage());
        try { Files.deleteIfExists(configFile); } catch (IOException ignored) {}
      }

      // NeoForge already loaded the bad values into memory for this session.
      // The corrupted file is gone, so next launch will regenerate fresh defaults.
      // This session continues with SafeConfig fallbacks for any bad values.
    }
  }

  /** Reads a config value, returning {@code fallback} if the stored type is wrong. */
  public static <T> T get(ModConfigSpec.ConfigValue<T> value, T fallback) {
    try {
      T result = value.get();
      return result != null ? result : fallback;
    } catch (ClassCastException | IllegalStateException | NullPointerException e) {
      warnOnce(e);
      return fallback;
    }
  }

  /** Reads a boolean config value safely. */
  public static boolean getBool(ModConfigSpec.BooleanValue value, boolean fallback) {
    try {
      return value.get();
    } catch (ClassCastException | IllegalStateException | NullPointerException e) {
      warnOnce(e);
      return fallback;
    }
  }

  /** Reads an int config value safely. */
  public static int getInt(ModConfigSpec.IntValue value, int fallback) {
    try {
      return value.get();
    } catch (ClassCastException | IllegalStateException | NullPointerException e) {
      warnOnce(e);
      return fallback;
    }
  }

  /** Reads a double config value safely, returning as float. */
  public static float getFloat(ModConfigSpec.DoubleValue value, float fallback) {
    try {
      return value.get().floatValue();
    } catch (ClassCastException | IllegalStateException | NullPointerException e) {
      warnOnce(e);
      return fallback;
    }
  }

  /** Reads an enum config value safely. */
  public static <E extends Enum<E>> E getEnum(ModConfigSpec.EnumValue<E> value, E fallback) {
    try {
      E result = value.get();
      return result != null ? result : fallback;
    } catch (ClassCastException | IllegalStateException | NullPointerException e) {
      warnOnce(e);
      return fallback;
    }
  }

  private static void warnOnce(Exception e) {
    String key = e.getClass().getSimpleName() + ": " + e.getMessage();
    if (WARNED.add(key)) {
      LOG.warn("Corrupted config value, using default (will not repeat): {}", key);
    }
  }
}
