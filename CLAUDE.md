# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

An Android TV / Google TV home screen (package `dev.wicolian.boxdecktv`) that also shows the user's
coding agents, read from [boxdeck](https://github.com/wicolian/boxdeck) over Tailscale. It is plain
Java against the Android SDK. There is no Gradle, no library dependencies and no test suite.

## Build and run

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@17 ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
sh build.sh                                   # → out/boxdeck-tv.apk (aapt2, javac, d8, zipalign, apksigner)
adb -s <TV>:5555 install -r out/boxdeck-tv.apk
sh install.sh <TV_IP>                         # first install: grant, Home role, screensaver, config
```

- `build.sh` generates `Apps.java` from `apps.txt`, the default top row, into `out/gen`.
- On the first build `build.sh` creates `key.jks`, which is git-ignored. Updates must be signed with
  the same key. If the key is lost, the app must be uninstalled first, and that drops its saved
  config and the Home role.
- There is no emulator setup. Verify on the real TV: `adb exec-out screencap -p > shot.png`, then look
  at it. Move focus with `adb shell input keyevent KEYCODE_DPAD_*`.

## Reaching the TV

The TV turns wireless debugging off at every boot. `WatchService.keepAdbOn` turns it back on, which
needs `WRITE_SECURE_SETTINGS` granted by `install.sh`. The pairing port is random after each boot:

```sh
dns-sd -B _adb-tls-connect._tcp local.        # name, then: dns-sd -L <name> _adb-tls-connect._tcp local.
adb connect <ip>:<port> && adb -s <ip>:<port> tcpip 5555 && adb connect <ip>:5555
```

## Architecture

- **`Theme`** holds 12 palettes (Omarchy's themes, Terminal, Classic, and an optional Custom) and the
  fonts: Inter by default, JetBrains Mono when chosen or for Terminal, both bundled in `assets/fonts`.
  Every screen reads `Theme.current(prefs)`. `accent` means focus or running; `warn` means it wants you.
- **Two layouts.** Shelf (`HeroRow` with a headline and agent cards, apps on a solid "glass" dock, and
  icons for inputs, settings and network) is the default. Grid is the original tv-detox layout. The
  `layout` pref picks one, and Classic is always Grid.
- **`MainActivity`** is the home screen, adapted from tv-detox:
  - App tiles, HDMI inputs, editing, and wallpapers.
  - **All configuration arrives as intent extras** on `am start -n …/.MainActivity`: `apps`,
    `quick`, `wallpaper`, `boxdeck_url`, `boxdeck_token`, `boxdeck_token_<machine>`, and `stamp`
    (applies a change once). `saveConfig` writes them to SharedPreferences `"config"`. There is no
    settings UI for boxdeck.
  - Layout is designed at 1920×1080 and scaled with `px()`.
  - **Theme colours are instance fields** (`fg`, `fgSoft`, `fgDim`, `fill`, `fillOn`, `edge`,
    `panelBg`, `dropBg`). The `Terminal` wallpaper swaps them in `onCreate` and recreates the
    activity on change, so use these fields instead of literal colours.
- **`Deck`** is the only network code:
  - It reads the hub's `/api/boxes`, then each machine's `/api/agents`.
  - Each machine has its own read-only token. The hub's is `boxdeck_token`. Others are
    `boxdeck_token_<name>`, where `<name>` is the name boxdeck uses.
  - It drops duplicate rows. Hubs list a machine again as `discovered:true`; configured rows win.
  - An agent counts as waiting when its herdr `agent_status` is `idle` or `done`. It counts as a pane
    only when it has a status. Agents outside herdr have none.
- **`AgentsPanel`** (on Home), **`DeckActivity`** (full-screen herdr panes, opened with OK on the
  panel) and **`WatchService`** all render from `Deck`.
- **`Deck.snapshot`** adds the hub's optional `/api/github` (PRs and CI, fetched by boxdeck with its
  own `gh`) to the machines. The panel, the watcher and the screensaver all read snapshots.
- **`WatchService`** is a foreground service (`specialUse`). The home screen starts it, and Android
  opens the home screen at boot. It runs every 30 s and does three things:
  - Shows toasts on changes only: offline, waiting, CI failing, quota under `Deck.QUOTA_LOW`, disk
    over `Box.DISK_FULL`. A toast is cut at **2 lines**: the most urgent line, then "+ N more". The
    first poll only seeds the state. While the screen is off nothing shows. On `SCREEN_ON` after 30
    minutes or more, it shows a "While you were away" summary instead. To test how toasts look:
    `adb shell am start -n dev.wicolian.boxdecktv/.MainActivity --ez demo true`. The service itself is
    not exported. Netflix blocks screenshots, so test toasts over Settings.
  - Keeps wireless debugging on.
  - Sends Tailscale's `com.tailscale.ipn.CONNECT_VPN` broadcast to `.IPNReceiver`. Tailscale does not
    reconnect after a boot, and setting `always_on_vpn_app` from adb is not honoured.
- **`MatrixDream`** is the screensaver (a `DreamService`). It draws into a half-resolution bitmap and
  scales it up, so it stays cheap on slow TVs. Above the clock it draws agents and usage from a
  snapshot every 30 s. On an error it shows nothing.
- App resources are only `res/drawable/icon.xml`, a vector icon. `build.sh` runs `aapt2 compile` on
  `res/`.
- To test the screensaver, set `settings put system screen_off_timeout 15000` and restore `300000`
  afterwards. Android's `UndimDetectorWakeLock` blocks the screensaver for a few minutes after
  repeated quick dims.

## Performance rules (measured on the TV's PowerVR GPU)

- **No full-screen gradients drawn per frame.** `themed()` paints the canvas once into a bitmap.
- **No translucent surfaces over the canvas.** `solid()` pre-mixes the colour. Blending cost 36 ms of
  every frame.
- **No elevation or translationZ shadows on focus.** Use zoom and the accent ring.
- **No continuous 60 fps animation.** The breathing light is one Handler loop at 15 fps, and it stops
  in `onPause`.
- Measure before and after with `adb shell dumpsys gfxinfo dev.wicolian.boxdecktv framestats`. The
  gap from `SwapBuffers` to `FrameCompleted` is GPU wait. Target: 0 janky frames while moving focus.

## Rules that are easy to break

- **Use read-only boxdeck tokens only** (`bdr_`, from `boxdeck token new --read-only`). A full `bd_`
  token can call `/api/run`, which is a shell on the machine. The read-only allowlist lives in boxdeck,
  so a new endpoint the TV needs must be added to that allowlist in boxdeck first.
- **Use Tailscale IPs, not MagicDNS names.** The TV uses AdGuard Private DNS, so names like `box`
  don't resolve. Hub rows carry the `url` each machine has in the hub config.
- **Never tint app banners.** People find an app by its logo colour. Terminal green is for chrome
  and focus only.
- **Amber (`AgentsPanel.WAITING`) means "act on this"**: an agent waiting, quota low, a machine down,
  or CI failing. Don't use it for anything else.
- On Google TV the stock launcher (`launcherx`, intent priority 2) beats the Home role. The real TV
  has `launcherx` and `setupwraith` disabled. Re-enable them before uninstalling this app, or the TV
  has no home screen.
