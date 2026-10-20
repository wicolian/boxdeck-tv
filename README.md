# boxdeck-tv

An Android TV home screen that shows your coding agents. From the sofa you see which Claude Code and
Codex agents are working and which ones stopped and want you, on every machine
[boxdeck](https://github.com/wicolian/boxdeck) watches, over Tailscale.

![Home screen](docs/home.png)

## What you get

- **A plain home screen.** Your apps, your HDMI inputs and a clock. No ads, no rows of suggestions.
  App logos keep their own colours, because that is how you find an app from across the room.
- **An agents panel** next to the clock: per machine, how many agents are working and how many are
  waiting, plus the Claude 5-hour and 7-day quota and today's cost at list price. Waiting is the only
  figure in amber, because it is the only one you act on.
- **Every herdr pane, full screen.** Press OK on the panel. Each pane shows its title, folder, model and
  age, waiting first. Agents that run outside herdr have no status, so they fold
  into one line per machine.

  ![Panes](docs/panes.png)

- **"Needs you" toasts.** When a pane goes from working to waiting, the TV shows a toast for a few
  seconds, even during Netflix. The first read after a restart only learns the state, so a reboot
  never floods the screen.
- **A terminal look** (the `Terminal` wallpaper): shell prompt, block cursor, monospace text and faint
  scanlines. There are 14 other wallpapers under Settings > Wallpaper.
- **A Matrix screensaver** with a small clock, drawn at half resolution so a slow TV keeps up.
- **It stays reachable after a restart.** Google TV turns wireless debugging off at every boot, and
  Tailscale does not reconnect by itself. The watcher turns both back on.

## Set up

You need a TV running Android TV or Google TV with developer mode, `adb`, Java 17 and the Android SDK
(platform 34, build-tools 34), and boxdeck built from `main` after [PR #51](https://github.com/wicolian/boxdeck/pull/51) (read-only tokens) on each machine.

1. **Tailscale on the TV.** Install it from the Play Store and sign in.
2. **A read-only token on each machine.** On every machine that runs boxdeck:

   ```sh
   boxdeck token new --read-only tv
   ```

   A read-only token (`bdr_...`) can only read `/api/boxes`, `/api/agents`, `/api/usage` and
   `/api/health`. **Never put a full token (`bd_...`) on a TV.** A full token can run commands on the
   machine through `/api/run`.
3. **Build and install.**

   ```sh
   sh build.sh
   BOXDECK_URL=http://<hub tailscale ip>:8100 \
   BOXDECK_TOKEN=bdr_...        \  # the hub's read-only token
   BOXDECK_TOKEN_MAC=bdr_...    \  # one per other machine, named as boxdeck names it
   sh install.sh <TV_IP>
   ```

   Use the hub's Tailscale IP, not its name. If the TV uses Private DNS, MagicDNS names do not resolve.

The hub's `/api/boxes` lists every machine, and the TV reads each machine's `/api/agents` with that
machine's token. To add a machine to the hub, add it to `boxes` in the hub's boxdeck config.

## The TV's own home screen

On most Google TVs the stock home screen still wins the Home button, even after `install.sh`. The only
way past it is to turn it off:

```sh
adb shell pm disable-user --user 0 com.google.android.apps.tv.launcherx
adb shell pm disable-user --user 0 com.google.android.tungsten.setupwraith
```

**This has a real risk.** On some Google TVs, a Chromecast for example, turning these off left the
device stuck restarting, and only a factory reset fixed it. Do it only if you can reach the TV over
adb after a restart. This app's wireless-debugging keeper exists for exactly that. It worked on an
Acerpure ACP75P (Google TV 14) through several restarts. To undo it, run `pm enable` on both packages.
For removing ads and bloat, see [tv-detox](https://github.com/Ansub/tv-detox), whose home screen this
one grew from.

## Permissions, and why

| Permission | Why |
|---|---|
| `INTERNET` | Reading boxdeck. Nothing else talks to the network. |
| `FOREGROUND_SERVICE`, `..._SPECIAL_USE` | The watcher, so toasts work while another app is open. |
| `WRITE_SECURE_SETTINGS` | Only when granted over adb. Turns wireless debugging back on. To revoke: `adb shell pm revoke dev.wicolian.boxdecktv android.permission.WRITE_SECURE_SETTINGS`. |
| cleartext HTTP | boxdeck on the tailnet is plain HTTP inside WireGuard. |

The watcher also sends Tailscale's own `CONNECT_VPN` broadcast, at boot and when boxdeck can't be
reached, at most once every 2 minutes.

## Credits

The home screen code (tiles, inputs, wallpapers, editing) comes from
[Ansub/tv-detox](https://github.com/Ansub/tv-detox) under the MIT license. The agents panel, the panes
view, the watcher, the terminal look and the screensaver are new here. MIT, see [LICENSE](LICENSE).
