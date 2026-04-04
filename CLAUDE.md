# Minecraft Mods Workspace

Multi-mod NeoForge 1.21.1 workspace. All mods share KwahsCore as a build-time library.

## Environment

- NeoForge 1.21.1 (version 21.1.222)
- Java 21 with Parchment mappings (2024.11.17)
- Nix flakes for dev environment (where available)

## KwahsCore Integration

KwahsCore is a shared library shaded into each mod's jar. Users never download it separately.

### Build setup (per mod)

```groovy
// gradle.properties
kwahscore_path=../KwahsCore

// build.gradle
sourceSets.main.java.srcDir "${kwahscore_path}/src/main/java"
```

That's it. The srcDir compiles KwahsCore source directly into the mod's jar as part of its source set.

**Do NOT use jarJar for KwahsCore.** jarJar embeds a separate jar that NeoForge loads as its own module. When multiple mods each shade KwahsCore via srcDir, the jarJar'd copy creates a duplicate module exporting the same package, which crashes the Java module system with `ResolutionException: Modules X and kwahs.core export package xyz.kwahson.core`.

### Why srcDir instead of composite build

NeoForge's module classloader only loads classes registered through `sourceSet()` in the `mods {}` block. Composite builds put dependencies on the compile classpath but not on the module path at runtime. The srcDir approach compiles library source as part of the mod's source set, so everything is in the same module.

## Config Patterns

### Config definition

Use `ModConfigSpec` with `CLIENT` type for client-only mods, `COMMON` for gameplay settings needed on servers.

### Config screen

Use KwahsCore's builder API:

```java
KwahsConfigScreen.builder("Mod Name", parent, MY_SPEC)
    .tab("General", tab -> {
        tab.sections("Left Column", "Right Column");
        tab.left(tab.toggle("Feature", MY_BOOL));
        tab.right(tab.intSlider("Range", "px", 0, 100, 5, MY_INT));
        tab.nextRow();
    })
    .build();
```

Register in mod constructor:
```java
container.registerConfig(ModConfig.Type.CLIENT, MyConfig.SPEC);
container.registerExtensionPoint(IConfigScreenFactory.class,
    (mc, parent) -> MyConfigScreen.create(parent));
```

### Config reads: always use SafeConfig

Never call `.get()` directly on config values. Always use SafeConfig wrappers:

```java
SafeConfig.getBool(MY_BOOL, defaultValue)
SafeConfig.getInt(MY_INT, defaultValue)
SafeConfig.getFloat(MY_DOUBLE, defaultValue)
SafeConfig.getEnum(MY_ENUM, defaultValue)
SafeConfig.get(MY_LIST, List.of())
```

This prevents crashes from corrupted/stale config files. Logs a warning once per key, returns the fallback.

### Config validation at startup

Call once on first tick (config is not loaded at mod construction time):

```java
if (!configValidated && MyConfig.SPEC.isLoaded()) {
    configValidated = true;
    SafeConfig.validateOrReset(MOD_ID, MyConfig.SPEC,
        MyConfig.SOME_VALUE, MyConfig.ANOTHER_VALUE);
}
```

This renames corrupted files to `.corrupted` and lets NeoForge regenerate defaults on next launch.

### Per-tick config caching

For values read on the render path (every frame), cache them once per tick as primitives:

```java
// In a tick handler (20/sec)
cachedSize = SafeConfig.getInt(MyConfig.SIZE, 160);

// In render (60+ FPS) -- read the cached primitive, zero overhead
int size = cachedSize;
```

## Code Style

- Packages: `dev.<modname>` (e.g., `dev.mapnhud`, `dev.torches`)
- KwahsCore package: `xyz.kwahson.core`

## Project Structure

Each mod is a standalone Gradle project with its own git repo:

```
Minecraft/
  KwahsCore/          -- shared library (xyz.kwahson.core)
  Map/                 -- Kwah's Map n Hud (dev.mapnhud)
  Torches/             -- Torches (dev.torches)
  ShulkerAccessory/    -- (dev.shulkeraccessories)
  CraftingTableAccessory/ -- (dev.craftingaccessory)
  CustomPortalsFoxified/  -- (dev.customportalsfoxified)
  Excavate/            -- (dev.excavate)
  LockEssentials/      -- (dev.lockessentials)
  TheFletchingTable/   -- (dev.thefletchingtable)
```

## Running Mods

Each mod with a Nix flake has a "Run Client" VS Code task:
```
nix develop --command bash -c 'gradle createLaunchScripts && bash build/moddev/runClient.sh'
```