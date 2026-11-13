#!/bin/sh
# Lance SWAT 4 (jeu Windows 32 bits, DirectX 8.1/9) dans le runtime Linux de
# l'application Android.
#
# Chaine de rendu et d'entree :
#   touche Android -> InputBridge (app) -> socket -> xbridge (XTest) -> Xvfb
#   Xvfb -> Mesa Zink -> Vulkan Turnip -> GPU Adreno
#   SWAT 4 (i386) -> Box86 -> Proton-Wine 32 bits -> wined3d (OpenGL) -> Zink
#   son : wined3d/ALSA -> /tmp/audio.fifo -> AudioTrack (cote application)
#
# Variables fournies par l'application :
#   SWAT4_WIDTH, SWAT4_HEIGHT   resolution de l'ecran X virtuel
#   SWAT4_INPUT_PORT             port TCP du pont d'entree (cote application)
#   SWAT4_RENDERER               gpu (Turnip+Zink) ou software (llvmpipe)
#   SWAT4_LOG_DIR                dossier lisible par l'application pour le journal
#   SWAT4_GAME_DIR               dossier du jeu (prefixe Wine inclus)

set -e
W=${SWAT4_WIDTH:-1280}
H=${SWAT4_HEIGHT:-720}
GAME_DIR=${SWAT4_GAME_DIR:-/game}
WINEPREFIX_DIR="$HOME/.wine"

mkdir -p /tmp/fb
rm -f /tmp/.X0-lock /tmp/.X11-unix/X0 /tmp/fb/Xvfb_screen0

Xvfb :0 -screen 0 "${W}x${H}x24" -fbdir /tmp/fb -nolisten tcp -ac >/tmp/xvfb.log 2>&1 &
XVFB_PID=$!
i=0
while [ ! -S /tmp/.X11-unix/X0 ] && [ $i -lt 100 ]; do sleep 0.1; i=$((i + 1)); done
if [ ! -S /tmp/.X11-unix/X0 ]; then
    echo "ECHEC Xvfb n'a pas demarre" >&2
    cat /tmp/xvfb.log >&2 || true
    exit 3
fi

export DISPLAY=:0

# --- Pont d'entree (XTest) : relaie les evenements tactiles vers Xvfb -------
if [ -n "$SWAT4_INPUT_PORT" ]; then
    /opt/ut/xbridge "$SWAT4_INPUT_PORT" >/tmp/xbridge.log 2>&1 &
    BRIDGE_PID=$!
fi

cleanup() {
    [ -n "$BRIDGE_PID" ] && kill "$BRIDGE_PID" 2>/dev/null || true
    kill "$XVFB_PID" 2>/dev/null || true
}
trap cleanup EXIT

# --- Rendu GPU : OpenGL -> Zink -> Vulkan (Turnip sur Adreno, malisx2 sur Mali)
# Xvfb n'a pas de DRI3 : Zink passe par le chemin "drisw" (LIBGL_KOPPER_DRI2)
# et la presentation Vulkan se fait en copie logicielle (MESA_VK_WSI_DEBUG=sw).
#   turnip   : GPU Qualcomm Adreno  (/dev/kgsl-3d0, pilote noyau KGSL)
#   mali     : GPU Arm Mali        (/dev/mali0,    pilote noyau mali_kbase)
#   software : rendu Mesa llvmpipe, tres lent mais fonctionnel
RENDERER=${SWAT4_RENDERER:-software}
if [ "$RENDERER" = "turnip" ] && [ -d /opt/turnip ] && [ -r /dev/kgsl-3d0 ]; then
    export LD_LIBRARY_PATH=/opt/turnip/lib:${LD_LIBRARY_PATH:-}
    export __GLX_VENDOR_LIBRARY_NAME=mesa
    export MESA_LOADER_DRIVER_OVERRIDE=zink
    export LIBGL_KOPPER_DRI2=1
    export MESA_VK_WSI_DEBUG=sw
    export VK_ICD_FILENAMES=/opt/turnip/share/vulkan/icd.d/freedreno_icd.aarch64.json
    export VK_DRIVER_FILES=/opt/turnip/share/vulkan/icd.d/freedreno_icd.aarch64.json
    export XDG_RUNTIME_DIR=/tmp
elif [ "$RENDERER" = "mali" ] && [ -f /opt/mali/libvulkan_malisx2.so ] && [ -r /dev/mali0 ]; then
    export LD_LIBRARY_PATH=/opt/mali:${LD_LIBRARY_PATH:-}
    export __GLX_VENDOR_LIBRARY_NAME=mesa
    export MESA_LOADER_DRIVER_OVERRIDE=zink
    export LIBGL_KOPPER_DRI2=1
    export MESA_VK_WSI_DEBUG=sw
    export VK_ICD_FILENAMES=/opt/mali/malisx2_icd.aarch64.json
    export VK_DRIVER_FILES=/opt/mali/malisx2_icd.aarch64.json
    export XDG_RUNTIME_DIR=/tmp
else
    echo "Rendu logiciel (llvmpipe) : $RENDERER" >&2
    export LIBGL_ALWAYS_SOFTWARE=1
    export GALLIUM_DRIVER=llvmpipe
fi

# --- Wine ------------------------------------------------------------------
# Wine 32 bits (x86 pur) : le jeu est un binaire 32 bits et Wine n'existe pas
# en 32 bits pour ARM64.  Box86 traduit le i386 en ARM64.
# Box86 emule lui-meme libc.so.6 / libdl.so.2 / libpthread.so.0 : aucune
# glibc i386 reelle n'est necessaire (ni presente dans le rootfs).
PROTON_DIR=/opt/wine
export WINEPREFIX="$WINEPREFIX_DIR"
export WINEARCH=win32
# DLL natives (PE) : repertoire i386-windows de l'installation Wine.
export WINEDLLPATH="$PROTON_DIR/lib/wine/i386-windows"
# Chargeur ELF de Wine (module unix) : chemin explicite, sinon Wine le cherche
# lui-meme via /proc/self/exe, ce qui echoue sous proot.
export WINELOADER="$PROTON_DIR/lib/wine/i386-unix/wine-preloader"
export PATH="$PROTON_DIR/bin:$PATH"

# DXVK n'existe pas en 32 bits : wined3d (OpenGL) fait le travail, et
# repasse par Zink/Turnip comme le moteur natif de l'UT2004.
export WINEDLLOVERRIDES="winemenubuilder.exe=d"
export BOX86_DYNAREC=1
# Repertoire ou Box86 cherche les bibliotheques i386 du programme invite
# (modules unix de Wine).
export BOX86_LD_LIBRARY_PATH="$PROTON_DIR/lib/wine/i386-unix:$PROTON_DIR/lib"

# --- Journal du jeu ---------------------------------------------------------
if [ -n "$SWAT4_LOG_DIR" ]; then
    mkdir -p "$SWAT4_LOG_DIR"
    rm -f "$SWAT4_LOG_DIR/Swat4.log"
    ln -sf "$SWAT4_LOG_DIR/Swat4.log" "$GAME_DIR/Content/System/Swat4.log" 2>/dev/null || true
fi

cd "$GAME_DIR/Content/System"

# Box86 execute le binaire i386 de Wine ; -w force le chemin de travail.
BOX86=${BOX86:-/opt/box86/box86}
STATUS=0
"$BOX86" -w "$GAME_DIR/Content/System" "$PROTON_DIR/bin/wine" Swat4.exe "$@" || STATUS=$?

exit $STATUS
