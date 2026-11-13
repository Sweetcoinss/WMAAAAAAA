# SWAT 4 for Android — unofficial launcher

Runs **SWAT 4** (Irrational Games / Paradox, 2005) on an ARM64 Android phone.

```
Android app (Java)            ← APK ~3 Mo : ne contient ni le jeu ni le moteur
  └─ proot ─ Debian arm64 ─ Xvfb (ecran X virtuel) ─┐
                                                       ├─ SWAT 4 (i386 Windows)
                                                       │    └─ Box86 (x86 → ARM64)
                                                       │         └─ Wine 32 bits (wined3d → OpenGL)
       touch → InputBridge ─ socket ─ xbridge (XTest) ──┘
       audio ──────────────────► ALSA FIFO ─► AudioTrack
```

**Au premier lancement**, l'application demande :

1. sur une connexion Wi-Fi, le téléchargement du **runtime Linux** (~120 Mo,
   Box86 + Wine 32 bits + Mesa Turnip/Zink), conservé ensuite sur l'appareil ;
2. l'endroit où se trouve votre installation de SWAT 4 (carte SD ou mémoire
   interne) : elle **vérifie** que `Content/System/Swat4.exe` y est présent,
   **enregistre le chemin**, puis **monte** ce dossier dans le runtime sans le
   copier.

C'est tout — aucun gigaoctet n'est copié, l'APK reste très petit.

## Touch controls

The keys are the ones the game itself uses (`Content/System/User.ini`,
`[Engine.Input]`):

| Touch | Game key | Action |
|---|---|---|
| left stick | `W` `A` `S` `D` | move / strafe |
| stick to the limit | `Shift` | run |
| drag on the right | mouse | look |
| **TIR** | left click | fire |
| **CMD** | right click | opens the game's own command interface |
| **ACT** | `F` | comply / interact |
| **RECH** | `R` | reload |
| **ACC** | `C` | crouch |
| **COURIR** | `Shift` | run |
| **PREND** | `G` | grab |
| **GAUCHE** / **DROITE** | `Q` / `E` | lean |
| **ARME+** | `/` | next weapon |
| **ZOOM** | `Z` | zoom |
| **LAMPE** | `V` | flashlight |

The command interface (orders to the team: stack up, open the door, breach…)
remains the game's own radial menu — it is opened with the **CMD** button and
then driven with the finger, exactly like a mouse.

## Build

```sh
# 1. runtime Linux (arm64) — Docker on an arm64 host or the CI
docker run --rm --platform linux/arm64 -v "$PWD:/src" debian:trixie \
    sh /src/android/runtime/build-runtime.sh

# 2. publish it as the GitHub "runtime-v1" release asset (rootfs.tgz)

# 3. APK
cd android && ./gradlew assembleRelease
```

GitHub Actions does all of it (`ubuntu-24.04-arm` for the runtime and the
`runtime-v1` release, `ubuntu-latest` for the APK).

## Layout

```
android/app/                      Java launcher (activity, input bridge, overlay)
android/runtime/
  build-runtime.sh                builds the Debian rootfs: Box86, Wine, Mesa
  build-mesa.sh                   Mesa Turnip + Zink
  xbridge/xbridge.c               XTest input injection
  rootfs-overlay/opt/ut/
    run-swat4.sh                  starts Xvfb, the input bridge and the game
    setup-wine.sh                 creates the Wine prefix, patches the game ini
docs/                             architecture and control mapping
```

## Legal

SWAT 4 and its assets are © Irrational Games / Paradox Entertainment. The
engine is based on Unreal Engine 2. This launcher is for personal use with a
copy you own; it does not contain any game data.
