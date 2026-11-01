# Thor Half-Life

Half-Life (GoldSrc) running natively on the **AYN Thor** dual-screen Android handheld, using
[Xash3D FWGS](https://github.com/FWGS/xash3d-fwgs) and
[hlsdk-portable](https://github.com/FWGS/hlsdk-portable) — with an **HEV companion deck** on the
bottom screen.

> Half-Life is a trademark of Valve Corporation. This project is not affiliated with or endorsed
> by Valve. It contains **no** Valve game data, logos or artwork; you need your own copy of
> Half-Life. Weapon silhouettes on the deck are decoded at runtime from *your* game files.

## What it does

| Screen | Content |
| --- | --- |
| Top (1920x1080) | The game, native resolution, clean: no health/armor/ammo/weapon menu/pickup history. Crosshair, pain indicators, messages and subtitles stay. |
| Bottom (1240x1080) | HEV deck: health and armor (big mono numbers, segmented bars, red when critical), suit status chip, flashlight charge; arsenal in slot columns 1–5 with clip/reserve/secondary ammo, current weapon highlighted, **tap = select**; pickup feed; hazards (Geiger meter, air timer under water, fire/shock/toxic/acid/cold/drowning/fall/train); compass tape with damage directions; chapter title + breadcrumb automap; quick buttons (flashlight, use, quick save, quick load with confirm, menu). |

Without a second display (or with *Screens: Single* in the launcher) the classic HUD stays on top.

### How the data flows

```
cl_dll (hlsdk)                      thorbridge (JNI)            Java (HevDeck, 20 Hz)
gHUD / gWR / gHR ──► thor_deck.cpp ──► ThorHUD_Snapshot() ──► JSON ──► draw
                       ▲                                                   │
                       └── ThorHUD_Command() ◄── whitelisted console cmd ◄─┘ (tap)
```

* `3rdparty/hlsdk-portable/cl_dll/thor_deck.{h,cpp}` builds a compact JSON snapshot every 50 ms on
  the game thread (health, battery, flashlight, damage bits, Geiger range, train, weapons with
  clip/ammo, pending selection, pickups, water level, fall speed, view angles, position, map).
* Deck actions are **console commands**, queued and executed on the game thread through
  `ClientCmd` — the same path as a key bind. Nothing writes game state directly. Allowed:
  `thor_select <id>`, `thor_wnext`, `thor_wprev`, `thor_use`, `impulse 100`, `savequick`,
  `loadquick`, `lastinv`, `+use/-use`, `cancelselect`, `thor_deck 0/1`.
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
| `/sdcard/ThorHalfLife/valve` | writable: `config.cfg`, `save/`, `engine.log`, `thor.cfg` (rewritten on every start), `thor_user.cfg` (yours) |

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
(`libclient_android_arm64.so`, `libhl_android_arm64.so`, `-DANDROID_APK=ON`) → mainui_cpp
(`libmenu.so`) → the engine with waf (`--android=arm64-v8a,,26`) → `libthorbridge.so` → Gradle.
The signing key is created once in `$THOR_BUILD_DIR/keystore` and reused.

Repository layout: this repo is a clone of xash3d-fwgs (upstream history, branch `thor`) with
hlsdk-portable (`mobile_hacks`) and its freevgui vendored as git subtrees under
`3rdparty/hlsdk-portable`.

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

**Thor Half-Life** startet Half-Life nativ auf dem AYN Thor (Xash3D FWGS + hlsdk-portable). Oben
läuft das Spiel in voller Auflösung ohne HUD (nur Fadenkreuz, Schadensrichtung, Meldungen), unten
zeigt das **HEV-Deck** Gesundheit, Panzerung, Anzugstatus und Taschenlampe, das Arsenal in den
Slot-Spalten 1–5 (Antippen = Waffe wählen, Munition im Magazin/Reserve/Zweitmunition),
aufgenommene Gegenstände, Gefahren (Geiger, Luft unter Wasser, Feuer, Strom, Gift, Säure, Kälte,
Ertrinken, Sturz, Zug), einen Kompass, Kapitelname und eine kleine Wegspur-Karte sowie
Schnelltasten (Lampe, Benutzen, Schnellspeichern, Schnellladen mit Bestätigung, Menü).

* Spieldaten: `/sdcard/xash/valve` wird nur gelesen. Konfiguration und Spielstände landen in
  `/sdcard/ThorHalfLife/valve`. Die offizielle Xash3D-App bleibt unberührt.
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
