# Kwah's Core -- TODO

## Config

- [x] `validateOrReset` should accept `ModConfig.Type` to build the correct filename (currently hardcodes `-client.toml`, breaks for COMMON and SERVER configs)
- [x] Deprecate `SafeConfig.getFloat` in favor of `getDouble` (marked @Deprecated, removal deferred to 1.0)
- [ ] Payload helpers: generic empty/single-value payload factories + registration helper
- [ ] Optional tooltips on config widgets -- surface the `ModConfigSpec` comment on hover
      so users can see the explanation inline. Builder API should accept a comment
      string (or pull it automatically from the underlying `ConfigValue`) and render it
      as a tooltip on the label/row.
- [ ] Scrolling behaviour for config screens -- long tabs currently overflow the screen
      with no way to reach clipped widgets. Needs a scroll container inside each tab
      (probably per-column so left/right scroll independently, or a single shared
      scrollbar at the tab level).
- [ ] Unified config screen across all Kwah mods -- investigate a single entry point
      that lists every installed Kwah mod and opens its config screen. Could live in
      KwahsCore as a registry (mods register their `Screen` factory at construction)
      with a root screen that enumerates them. Open question: how to launch it --
      dedicated keybind, mods menu entry, or a shared "Kwah mods" button injected
      into the pause/options screen.

## Map Render Widget

- [ ] Sizing is off -- the map render widget (used by Kwah's Map n Hud) has odd
      sizing behaviour. Unclear whether the bug lives in the Map mod's widget code
      or in a KwahsCore rendering helper. Investigate both sides before picking a
      fix location.

## Reorderable List

- [ ] Keyboard accessibility: arrow keys for selection, Shift+Up/Down for reorder, Space for toggle, Enter for settings

## Backlog

- [ ] Keybind-to-packet handler (if pattern repeats enough across mods)

## Sophisticated Storage compat (`src/compat/java/xyz/kwahson/compat/ss/`)

The non-placed-shulker compat layer ticks `ITickableUpgrade`s and persists menu
mutations through `SSVirtualHost` + `SSItemStorageMenu.broadcastChanges()`. The
upgrades below were intentionally left out of that pass.

### Deferred upgrades

- [ ] **Pickup upgrade** (`IPickupResponseUpgrade`) -- needs an `ItemEntityPickupEvent`
      hook that walks the wearer's equipped SS shulkers and offers each picked-up
      stack to their pickup wrappers before vanilla insert. Boat case is moot
      (no wearer). Feasible, just hasn't been wired.

- [ ] **Pump upgrade** (fluid I/O via `ITickableUpgrade`) -- ticks fine in
      `SSVirtualHost`, but pump targets blocks at the wrapper's `BlockPos`. For
      an accessory the position is the player's feet, which is meaningless and
      could drain/fill nearby blocks unexpectedly. For a boat the position moves
      with the entity, which is at least intentional but still surprising. Decide
      whether to gate pump off for accessories specifically, or off entirely.

- [ ] **Battery upgrade** (FE storage) -- passive container. The capability needs
      to be exposed through whatever the wearer/host presents to the FE network.
      No FE plumbing on accessories or boats today, so the battery just sits
      there holding charge no one can read or write. Needs a host-level capability
      provider.

- [ ] **Block converter upgrade** -- `ITickableUpgrade` that scans nearby blocks
      and converts them by recipe. Same problem as pump: `BlockPos` for an
      accessory is the wearer's feet, so it would convert the ground under the
      player every tick. Either gate off for non-block hosts or accept the
      gameplay implication.

### Permanently out of scope

- **Hopper / Advanced Hopper** (`INeighborChangeListenerUpgrade`) -- requires
      neighbor block I/O. There is no neighbor for an item-form shulker.

### Known persistence gaps (NOT fixed by the current hook)

The `SSItemStorageMenu.removed()` + `SSVirtualHost.tick()` `setDirty()` hooks
persist any mutation that lands in the shared contents NBT by reference.
Contents, sort item order, slot edits, upgrade install/remove (at the upgrade
slot level), and tickable upgrade contents mutations all work -- with a caveat:
`SSVirtualHost.tick()` rate-limits `setDirty()` to once per ~5 seconds per host
to avoid forcing a full `ItemContentsStorage` serialize on every autosave cycle.
This means a server crash can lose up to ~5 seconds of in-flight upgrade
mutations (magnet pickups, feeding consumption, etc.). `invalidate()` flushes
eagerly, so clean session boundaries (logout, respawn, swap) don't lose
anything. The following do NOT work and require a deeper fix:

- [ ] **Settings / preferences don't persist for stack wrappers.** Affects
      memory slots (item-locked slots), no-sort slots, item-display
      categories, render info overlays, `sortBy` preference, open tab ID,
      main/accent colors. Root cause: `StorageWrapper`'s constructor builds
      `SettingsHandler` with a captured reference to `settingsNbt`, but for
      `StackStorageWrapper` that reference is always an orphan `CompoundTag`
      -- either the field initializer (first-open, no UUID) or
      `tag.getCompound("settings")` returning a new empty compound because
      the persisted "settings" key is missing. Mutations land in the orphan
      and are never linked to `ItemContentsStorage`. `SettingsHandler.reloadFrom`
      does NOT rewire -- it just calls each category's reloadFrom, and the
      categories' save-back lambdas still capture the original orphan. Fix
      options: (a) mixin into `StorageWrapper` ctor to swap callbacks, (b)
      reflection patch to rebuild the settings handler with a live ref, (c)
      pre-seed "settings" and "renderInfo" compounds in the storageWrapper
      tag BEFORE `StackStorageWrapper.fromStack` runs (requires force-
      generating a UUID for fresh shulkers that have never been opened).

- [ ] **Upgrade configuration tabs reset on menu close.** Affects filter
      blocklists/allowlists on tickable upgrades (magnet filter, feeding
      filter, cooking input filter, etc.) and every other per-upgrade config
      that lives on the upgrade ItemStack's data components. Root cause: the
      user's UI mutation lands on the in-memory `ItemStack` held by the
      `UpgradeHandler`'s slot list, but the `UpgradeHandler` never
      re-serializes modified stacks back into its backing NBT because the
      no-op save handler fed to `StackStorageWrapper` never triggers a
      serialize. Even with `setDirty()` on `ItemContentsStorage`, the
      persisted tag still has the stale pre-mutation upgrade stacks because
      the handler's in-memory stacks never got written back. Same family of
      bug as the settings issue but one layer deeper -- fixing it requires
      either forcing `UpgradeHandler.saveInventory()` (or equivalent) on
      every mutation, or replacing the no-op save handler with one that
      triggers re-serialization before `setDirty()`.

### Architectural followups

- [ ] **Per-host attachment instead of static map** -- `SSAccessoryTicker`
      keeps a static `Map<UUID, Map<Integer, SSVirtualHost>>` and manually
      handles logout, respawn, and dimension-change purges. A NeoForge
      `AttachmentType<Map<Integer, SSVirtualHost>>` on the player entity
      would handle all three lifecycle events automatically (the attachment
      dies with the entity), and would let us drop the manual purges and the
      static state. Worth doing if the lifecycle list grows further.

- [ ] **Class-load isolation for SS code in ShulkerBoatEntity** -- the
      `tickSSUpgrades` method body contains symbolic references to
      `SSVirtualHost` (`new`, `checkcast`, `invokedynamic` for method refs).
      HotSpot defers resolution until the bytecode executes, and the
      `cachedIsSSShulker` guard prevents execution on no-SS installs, so it
      works today. But it's load-bearing that the entire method body is dead
      on no-SS. The standard NeoForge compat pattern is to extract SS-touching
      code into a separate `tickSSUpgradesImpl()` method so the JIT never
      compiles or resolves it unless actually called. Same treatment for the
      `setShulkerItem` invalidation cast. Low priority since it works, but
      makes the gate structural rather than relying on cooperative JIT
      behaviour.

- [ ] **Verify upgrade install/remove via menu** -- confirm that dragging an
      upgrade into the upgrade slot of an SS menu opened from an accessory/boat
      persists across world reload. The `removed()` hook in
      `SSItemStorageMenu` should cover it (the wrapper's NBT is the same
      reference held by `ItemContentsStorage`, and we mark dirty on close),
      but worth confirming once.

- [ ] **Crash-window mutation loss** -- `SSItemStorageMenu.removed()` marks
      the storage dirty once when the menu closes. If the server crashes
      while a menu is still open, mutations made during that session are
      lost (the in-memory NBT was updated, but the dirty flag was never set,
      so the autosave skipped the SavedData write). Acceptable tradeoff for
      the autosave I/O savings, but if we ever care about crash-recovery
      here we can track a per-menu dirty bit and call `setDirty()` once on
      first mutation rather than on close.
