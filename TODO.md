# Kwah's Core -- TODO

## Config

- [x] `validateOrReset` should accept `ModConfig.Type` to build the correct filename (currently hardcodes `-client.toml`, breaks for COMMON and SERVER configs)
- [x] Deprecate `SafeConfig.getFloat` in favor of `getDouble` (marked @Deprecated, removal deferred to 1.0)
- [ ] Payload helpers: generic empty/single-value payload factories + registration helper

## Reorderable List

- [ ] Keyboard accessibility: arrow keys for selection, Shift+Up/Down for reorder, Space for toggle, Enter for settings

## Backlog

- [ ] Keybind-to-packet handler (if pattern repeats enough across mods)
