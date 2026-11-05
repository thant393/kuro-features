# kuro-features

Optional feature implementations for [Kurogram](https://github.com/thant393/Kurogram).

The Kurogram core only contains the UI and the interfaces in `tw.kurocore.kurogram.feature`
(`ThemeStore`, later `SpecialContacts`, `ContactChanges`). This repository provides the real implementations.
Without this folder the core builds and runs with safe stubs.

## Use

Add it to the core as a git submodule at `kuro-features/`:

    git submodule add https://github.com/thant393/kuro-features kuro-features

`TMessagesProj/build.gradle` adds `kuro-features/src/main/java` to the source set only when the folder exists.

## Rules

- Only depend on public classes of the core. Never edit core files from here.
- `FeaturesBootstrap` is found by name; keep its package, name and public no-arg constructor.
- `FeaturesBootstrap.apiVersion()` must equal `KuroFeatures.API_VERSION` or the core ignores the module.

## Contents

| Feature | Class |
|---|---|
| Kuro Themes (Theme Store) | `ThemeStoreImpl` |
