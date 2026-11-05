# Thor Half-Life

Half-Life (GoldSrc) — plus the add-ons **Opposing Force** and **Blue Shift** — running natively on
the **AYN Thor** dual-screen Android handheld, using
[Xash3D FWGS](https://github.com/FWGS/xash3d-fwgs) and
[hlsdk-portable](https://github.com/FWGS/hlsdk-portable) — with an **HEV companion deck** on the
bottom screen.

> Half-Life is a trademark of Valve Corporation. This project is not affiliated with or endorsed
> by Valve. It contains **no** Valve game data, logos or artwork; you need your own copy of
> Half-Life. Weapon silhouettes on the deck are decoded at runtime from *your* game files.

## Quick start

▶ **[Watch the install guide](https://youtu.be/_ym4_NXosSs)** (2 min) · **[Full video](https://youtu.be/jiYEW7FgMcQ)**

1. Install the APK from the [releases](https://github.com/harmssi/thor-halflife/releases) (Chrome: *Download anyway* → *Package installer* → *Settings* → *Allow from this source* → *Install*).
2. Copy `steamapps/common/Half-Life/valve` from your Steam installation to `/sdcard/xash/valve`. For Opposing Force / Blue Shift put `gearbox` / `bshift` next to it (`*_hd` folders optional).
3. Start the app and tap **Allow file access**.
4. Play.

If it works for you, a ⭐ on GitHub helps.

## What it does

| Screen | Content |
| --- | --- |
| Top (1920x1080) | The game, native resolution, clean: no health/armor/ammo/weapon menu/pickup history. Crosshair, pain indicators, messages and subtitles stay. |
| Bottom (1240x1080) | HEV deck: health and armor (big mono numbers, segmented bars, red when critical), suit status chip, flashlight charge; arsenal in slot columns 1–5 (Opposing Force 1–7) with clip/reserve/secondary ammo, current weapon highlighted, **tap = select**; **Saves** tab next to the arsenal: your save games with save shot, chapter, map and time, **tap twice = load**, *Save* makes a new save (`saveNNN`, never overwrites); pickup feed; hazards (Geiger meter, air timer under water, fire/shock/toxic/acid/cold/drowning/fall/train); compass tape with damage directions; chapter title + breadcrumb automap; quick buttons (flashlight, use, quick save, quick load with confirm, menu). |

Without a second display (or with *Screens: Single* in the launcher) the classic HUD stays on top.

### Games

| Launcher | Game folder | Game libraries (hlsdk-portable branch) | Start map |
| --- | --- | --- | --- |
| Half-Life | `valve` | `3rdparty/hlsdk-portable` (`mobile_hacks`) → `libclient_android_arm64.so`, `libhl_android_arm64.so` | `c1a0` |
| Opposing Force | `gearbox` | `3rdparty/hlsdk-opfor` (`opforfixed`) → `libclient_gearbox.so`, `libopfor_android_arm64.so` | `of0a0` |
| Blue Shift | `bshift` | `3rdparty/hlsdk-bshift` (`bshift`) → `libclient_bshift.so`, `libbshift_android_arm64.so` | `ba_tram1` |

The launcher lists all three; a game without data is dimmed and its check list says what is
missing (the add-ons also need the Half-Life base data in `valve`). *Play* starts the selected game.

* The engine always asks for `libclient_android_arm64.so` from `XASH3D_GAMELIBDIR`. For the
  add-ons the app creates `files/gamelibs/<gamedir>/` with symlinks: that name → the game's own
  client library, all other libraries unchanged. Half-Life uses the APK library folder directly.
* The deck adapts per game: Opposing Force has 7 weapon slots (the column count comes from the
  snapshot, `"slots"`), up to 5 weapons per slot (rows grow), PCV armor and night vision instead
  of the flashlight (the client reads `CHudNightvision`, `THOR_DECK_NIGHTVISION`); Blue Shift
  shows the armor vest. Weapon silhouettes come from `<gamedir>_hd/sprites`, `<gamedir>/sprites`,
  then `valve_hd/sprites`, `valve/sprites` (a sprite rect without bright pixels gives no
  silhouette: Opposing Force's `weapon_penguin.txt` points at an empty part of `640hudof03`).
  Chapter names for all three games, taken from the maps that show the chapter title; Blue Shift
  visits `ba_teleport2` twice (from Xen: *Focal Point*, back from the power plant: *A Leap of Faith*).

### How the data flows

```
cl_dll (hlsdk)                      thorbridge (JNI)            Java (HevDeck, 20 Hz)
gHUD / gWR / gHR ──► thor_deck.cpp ──► ThorHUD_Snapshot() ──► JSON ──► draw
                       ▲                                                   │
                       └── ThorHUD_Command() ◄── whitelisted console cmd ◄─┘ (tap)
```

* `3rdparty/hlsdk-*/cl_dll/thor_deck.{h,cpp}` (identical in all three game trees) builds a compact JSON snapshot every 50 ms on
  the game thread (health, battery, flashlight, damage bits, Geiger range, train, weapons with
  clip/ammo, pending selection, pickups, water level, fall speed, view angles, position, map).
* Deck actions are **console commands**, queued and executed on the game thread through
  `ClientCmd` — the same path as a key bind. Nothing writes game state directly. Allowed:
  `thor_select <id>`, `thor_wnext`, `thor_wprev`, `thor_use`, `impulse 100`, `savequick`,
  `loadquick`, `save new`, `load <name>` (letters, digits, `_`, `-` only), `lastinv`, `+use/-use`, `cancelselect`, `thor_deck 0/1`.
* `thor_select <id>` does exactly what confirming the HUD weapon menu does:
  `ServerCmd("weapon_xxx")` + `g_weaponselect` + the select sound.
* `thor_wnext` / `thor_wprev` switch instantly (the menu is hidden on the top screen).
* The menu button sends a real Escape key press (opens the menu in game, steps back/closes it in
  the menu).
* cvar `thor_deck` (set from the app through `THOR_DECK=1`) hides the classic HUD elements
  (health, battery, ammo, weapon list, pickup history, flashlight, damage icons).

## Controller (Thor, Xbox semantics)

| Input | Action |
| --- | --- |
| Left stick / right stick | move / look |
| RT / LT | fire / alt fire |
| A / B / X / Y | jump / duck / reload / use |
| LB / RB | previous / next weapon (instant) |
| D-pad up / down / left / right | flashlight / last weapon / quick save / scores |
| L3 / R3 | walk / duck |
| Start / Back | menu / scores |

The layout lives in `thor/android/app/src/main/assets/gamepad_presets/thor.cfg` and is applied on
the first start (launcher: *Controller → apply Thor layout on next start* re-applies it). Gyro
aiming is off by default (`joy_gyro_enable 1` in `thor_user.cfg` to try it). No touch controls.

## Files and folders on the device

| Path | Use |
| --- | --- |
| `/sdcard/xash/valve` (+ `valve_hd`) | your Half-Life data, **read only** (`XASH3D_RODIR`) |
| `/sdcard/xash/gearbox`, `/sdcard/xash/bshift` (+ `_hd`) | your Opposing Force / Blue Shift data, read only |
| `/sdcard/ThorHalfLife/<gamedir>` | writable, one folder per game: `config.cfg`, `save/`, `engine.log`, `thor.cfg` (rewritten on every start), `thor_user.cfg` (yours) |

Copying the add-ons from a Steam installation: copy `gearbox` / `bshift` (and `gearbox_hd` /
`bshift_hd`) next to `valve`. For the German version merge `gearbox_german` / `bshift_german` into
the game folder **without** its `models/` and `violence.cfg` (those are the censored variants); on
the first start of each game the app also sets the `violence_*` cvars to 1.

The app needs *All files access* (launcher button, or
`adb shell appops set de.harmssi.thorhalflife MANAGE_EXTERNAL_STORAGE allow`).
It is a separate package (`de.harmssi.thorhalflife`) and does not touch the official Xash3D app.

## Building

Requirements: a Linux host with Docker and the `thor-build` image (`thor/Dockerfile`, shared with
Thor Conquer: NDK 27.2.12479018, SDK 35, Gradle 8.9, CMake 3.31).

```sh
export THOR_BUILD_HOST=user@linux-host-with-docker   # required
export THOR_BUILD_DIR=/mnt/cache/thor-halflife        # optional (default)
thor/build.sh            # -> dist/ThorHalfLife.apk
thor/build.sh --install  # + adb install -r (uses ANDROID_SERIAL)
THOR_STAGE=native thor/build.sh   # only native libs (no APK copy)
THOR_STAGE=apk thor/build.sh      # only restage + Gradle
```

`thor/docker-build.sh` builds, inside the container: SDL2 2.32.8 → hlsdk-portable
(`libclient_android_arm64.so`, `libhl_android_arm64.so`, `-DANDROID_APK=ON`), hlsdk-opfor and
hlsdk-bshift (same options; their client libraries are renamed when staged) → mainui_cpp
(`libmenu.so`) → the engine with waf (`--android=arm64-v8a,,26`) → `libthorbridge.so` → Gradle.
The signing key is created once in `$THOR_BUILD_DIR/keystore` and reused.

Repository layout: this repo is a clone of xash3d-fwgs (upstream history, branch `thor`) with
hlsdk-portable (`mobile_hacks`) and its freevgui vendored as git subtrees under
`3rdparty/hlsdk-portable`, and the hlsdk-portable branches `opforfixed` and `bshift` as subtrees
under `3rdparty/hlsdk-opfor` and `3rdparty/hlsdk-bshift` (their `freevgui` is a symlink to the
same freevgui). The Thor changes in each tree: `cl_dll/thor_deck.*`, the HUD hide checks in
`ammo.cpp`, `ammohistory.cpp`, `battery.cpp`, `flashlight.cpp`, `health.cpp`, the hooks in
`cdll_int.cpp`/`hud.cpp`, `friend struct ThorDeckAccess` in `hud.h`/`health.h`.

## Picture / gamma

The engine uses the GoldSrc gamma model (`gamma` 2.5, `brightness`, `texgamma` 2.0, `lightgamma`
2.5, `engine/client/gamma.c`); the values come from the game's own `config.cfg` (Steam defaults:
`gamma 2.5`, `brightness 1`) and are not touched by the app. A washed-out picture (black shown as
~30 % grey) was not gamma: Android drew its translucent *default focus highlight* over the SDL
surface after the engine had started/stopped text input (console). `GameActivity` disables the
focus highlight on the game views.

## Performance

Measured on the Thor (Snapdragon 8 Gen 2) with the GLES3 renderer (`-ref gles3compat`, default),
1920x1080, vsync: a steady **60 fps** while turning in c1a1 and c2a5 (SurfaceFlinger frame
timestamps: 126 frames / 2.08 s, max frame 16.7 ms); only the autosave on a level change hitches.
GL4ES and GLES1 can be chosen in the launcher.

## Licenses

* **Xash3D FWGS** (engine, renderers, filesystem): GPL-3.0-or-later.
* **mainui_cpp** (menu): GPL-2.0-or-later.
* **hlsdk-portable** (client/server game libraries) is derived from the **Half-Life 1 SDK** and
  under its license: copying, modifying and distributing (source and binaries) is allowed **only
  for free**, the LICENSE must be included, and the result is a modification of a Valve game for
  use with Half-Life. Consequences for this project:
  * the APK and sources must never be sold or bundled with anything paid;
  * the APK requires your own Half-Life files and ships none of them;
  * the SDK code stays in its own libraries (`libclient`, `libhl`) that the GPL engine loads at
    runtime through the documented game-DLL interface — the same arrangement FWGS uses for its
    official builds; our HUD snapshot code (`thor_deck.*`) is part of that SDK-licensed client
    library, and the JNI bridge only looks up two exported C functions;
  * no Valve artwork or logo is in the repository or the app UI (icon and design are original;
    weapon silhouettes are read from the user's game files at runtime).
* **SDL2**: zlib. **Inter**, **JetBrains Mono**: SIL OFL 1.1. **freevgui**: see its LICENSE.
* Thor Half-Life's own code (Java app, `thorbridge.c`): GPL-3.0-or-later.

All license texts are in the app (*Licenses* button) and in
`thor/android/app/src/main/assets/licenses/`.

---

## Deutsch

**Thor Half-Life** startet Half-Life (sowie Opposing Force und Blue Shift) nativ auf dem AYN Thor (Xash3D FWGS + hlsdk-portable). Oben
läuft das Spiel in voller Auflösung ohne HUD (nur Fadenkreuz, Schadensrichtung, Meldungen), unten
zeigt das **HEV-Deck** Gesundheit, Panzerung, Anzugstatus und Taschenlampe, das Arsenal in den
Slot-Spalten 1–5 (Antippen = Waffe wählen, Munition im Magazin/Reserve/Zweitmunition),
aufgenommene Gegenstände, Gefahren (Geiger, Luft unter Wasser, Feuer, Strom, Gift, Säure, Kälte,
Ertrinken, Sturz, Zug), einen Kompass, Kapitelname und eine kleine Wegspur-Karte sowie
Schnelltasten (Lampe, Benutzen, Schnellspeichern, Schnellladen mit Bestätigung, Menü).
Der Reiter **Spielstände** neben dem Arsenal listet die Speicherstände mit Vorschaubild, Kapitel,
Karte und Uhrzeit: zweimal antippen = laden, *Speichern* legt einen neuen Stand an (`saveNNN`).

* Spiele: Half-Life, **Opposing Force** und **Blue Shift** (Auswahl im Launcher; ein Spiel ohne
  Daten wird gedimmt angezeigt, die Prüfliste nennt, was fehlt). Bei Opposing Force hat das Deck
  7 Waffenslots, PCV-Panzerung und Nachtsicht statt Taschenlampe.
* Spieldaten: `/sdcard/xash/valve`, `/sdcard/xash/gearbox`, `/sdcard/xash/bshift` werden nur
  gelesen (die Add-ons brauchen zusätzlich `valve`). Konfiguration und Spielstände landen je Spiel
  in `/sdcard/ThorHalfLife/<Spielordner>`. Die offizielle Xash3D-App bleibt unberührt.
* Deutsche Steam-Version: `gearbox_german`/`bshift_german` ohne `models/` und `violence.cfg` in
  den Spielordner mergen (sonst zensiert); beim ersten Start setzt die App `violence_*` auf 1.
* Erster Start: im Launcher „Dateizugriff erlauben“, dann „Spielen“. Einstellungen: Bildschirme
  (Dual/Einzeln), Renderer (GLES3/GL4ES/GLES1), Sprache (System/EN/DE/ES/FR), Controller-Belegung.
* Controller (Xbox-Logik nach Aufdruck): RT Feuer, LT Zweitfeuer, A Springen, B Ducken,
  X Nachladen, Y Benutzen, LB/RB Waffe zurück/vor, Steuerkreuz oben Lampe, unten letzte Waffe,
  links Schnellspeichern, Start Menü.
* Bauen: `THOR_BUILD_HOST=user@host thor/build.sh` (Docker-Image `thor-build`), Ergebnis
  `dist/ThorHalfLife.apk`.
* Lizenz-Hinweis: Die Spielbibliotheken stammen aus dem Half-Life-1-SDK und dürfen nur kostenlos
  weitergegeben werden; das Projekt enthält keine Spieldaten, Logos oder Grafiken von Valve.
  Half-Life ist eine Marke der Valve Corporation; keine Verbindung zu Valve.
