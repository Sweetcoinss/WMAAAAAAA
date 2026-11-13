#!/bin/sh
# Prepare le prefixe Wine et les reglages de SWAT 4 pour l'ecran X virtuel.
#
# Usage : setup-wine.sh <dossier du jeu> [largeur] [hauteur]
#
# Le prefixe Wine est cree une seule fois (wineboot).  Le jeu est un binaire
# i386 : Wine 32 bits de Proton tourne sous Box86.
set -e
GAME_DIR=${1:-/game}
W=${2:-1280}
H=${3:-720}
PROTON_DIR=/opt/wine
WINEPREFIX="$HOME/.wine"

export WINEPREFIX
export WINEARCH=win32
export WINEDLLPATH="$PROTON_DIR/lib/wine/i386-windows:$PROTON_DIR/lib/wine/i386-unix"
export LD_LIBRARY_PATH="/opt/box86/i386/lib:/opt/box86/i386/usr/lib:$PROTON_DIR/files/lib:${LD_LIBRARY_PATH:-}"
export BOX86_L2S=1
export BOX86_DYNAREC=1
export BOX86_NOALTL=1
export WINEDLLOVERRIDES="winemenubuilder.exe=d"
BOX86=${BOX86:-/opt/box86/box86}
WINE="$PROTON_DIR/bin/wine"

if [ ! -x "$BOX86" ]; then
    echo "ECHEC box86 absent : $BOX86" >&2
    exit 2
fi
if [ ! -x "$WINE" ]; then
    echo "ECHEC Wine 32 bits absent : $WINE" >&2
    exit 2
fi

mkdir -p "$HOME" "$GAME_DIR/Content/System"

# --- Prefixe Wine (une seule fois) ----------------------------------------
if [ ! -f "$WINEPREFIX/system.reg" ]; then
    echo "STEP prefixe Wine"
    mkdir -p "$WINEPREFIX"
    # Initialisation minimal du prefixe : le serveur X est demarre par run-swat4.sh,
    # on utilise ici un Xvfb jetable pour que wineboot puisse creer sa fenetre.
    if [ -z "$DISPLAY" ]; then
        Xvfb :9 -screen 0 640x480x24 -nolisten tcp -ac >/tmp/xvfb-setup.log 2>&1 &
        SETUP_XVFB=$!
        export DISPLAY=:9
        sleep 1
    fi
    "$BOX86" "$WINE" wineboot --init 2>/dev/null || \
        "$BOX86" "$WINE" wineboot 2>/dev/null || true
    if [ -n "$SETUP_XVFB" ]; then
        kill "$SETUP_XVFB" 2>/dev/null || true
    fi
    # Le prefixe 32 bits a besoin de ces DLL pour le multi-media et le DirectX.
    for d in d3d9 d3d8 dxgi d3d11 ddraw opengl32 winmm; do
        if [ -f "$PROTON_DIR/lib/wine/i386-windows/$d.dll" ]; then
            cp -f "$PROTON_DIR/lib/wine/i386-windows/$d.dll" \
                  "$WINEPREFIX/drive_c/windows/system32/$d.dll" 2>/dev/null || true
        fi
    done
fi

# --- Reglages du jeu -------------------------------------------------------
INI="$GAME_DIR/Content/System/Swat4.ini"
if [ -f "$INI" ]; then
    cp -f "$INI" "$INI.bak" 2>/dev/null || true
    set_ini() {  # set_ini <section> <cle> <valeur>
        sed -i "s|^$2=.*|$2=$3|" "$INI"
    }
    # Resolution de la fenetre : celle de l'ecran X virtuel
    set_ini "WinDrv.WindowsClient" "WindowedViewportX" "$W"
    set_ini "WinDrv.WindowsClient" "WindowedViewportY" "$H"
    set_ini "WinDrv.WindowsClient" "FullscreenViewportX" "$W"
    set_ini "WinDrv.WindowsClient" "FullscreenViewportY" "$H"
    set_ini "WinDrv.WindowsClient" "MenuViewportX" "$W"
    set_ini "WinDrv.WindowsClient" "MenuViewportY" "$H"
    # Plein ecran : le jeu est rendu dans la fenetre X, pas sur l'ecran Android
    set_ini "WinDrv.WindowsClient" "StartupFullscreen" "False"
    # Pas d'assistant de premier lancement (il demande une resolution)
    set_ini "FirstRun" "FirstRun" "0"
    # Sensibilite de la souris : le pont tactile envoie des deplacements relatifs
    set_ini "WinDrv.WindowsClient" "MouseXMultiplier" "0.35"
    set_ini "WinDrv.WindowsClient" "MouseYMultiplier" "0.35"
    # Pas de joystick (le tactile passe par la souris)
    set_ini "WinDrv.WindowsClient" "UseJoystick" "False"
    # Audio : ALAudio (OpenAL) vers la sortie ALSA du runtime
    set_ini "Engine.Engine" "AudioDevice" "ALAudio.ALAudioSubsystem"
    # Rendu : DirectX, servi par wined3d (OpenGL) sous Wine
    set_ini "Engine.Engine" "RenderDevice" "D3DDrv.D3DRenderDevice"
    # Pas de cinematiques d'introduction au demarrage (Bink) : on les saute
    set_ini "Engine.GameEngine" "EnableDevTools" "False"
fi

# --- Dossiers de sauvegarde ------------------------------------------------
mkdir -p "$GAME_DIR/Content/Save" "$GAME_DIR/Content/Cache" 2>/dev/null || true

echo "STEP prefixe pret"
