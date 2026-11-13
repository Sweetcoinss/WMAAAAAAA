#!/bin/sh
# Construit le runtime Linux embarque dans l'APK : android/runtime/out/rootfs.tgz
#
# Contenu pour SWAT 4 (jeu Windows 32 bits) :
#   - Box86 : traduit le binaire i386 du jeu et de Wine en ARM64
#   - bibliotheques C i386 (glibc Debian i386) : Box86 execute la glibc i386
#   - Wine 32 bits (Kron4ek/Wine-Builds, archive x86)
#   - Mesa Turnip + Zink (GPU Adreno), compile par build-mesa.sh
#   - ecran X virtuel (Xvfb) et pont d'entree XTest (xbridge)
#   - outils d'installation des donnees du jeu (7-Zip, unshield)
#
# S'execute en root dans un conteneur Debian 13 (trixie) arm64, le depot monte en /src :
#   docker run --rm --platform linux/arm64 -v "$PWD:/src" debian:trixie sh /src/android/runtime/build-runtime.sh
set -e
RUNTIME=/src/android/runtime
OUT=$RUNTIME/out
export DEBIAN_FRONTEND=noninteractive

BOX86_VERSION=v0.3.8
WINE_VERSION=11.21
MALISX2_VERSION=0.5.0

# 1. Bibliotheques systeme du runtime
apt-get update -q
apt-get install -y -q --no-install-recommends \
    libstdc++6 libatomic1 libgl1 libglx0 libopengl0 libgl1-mesa-dri libglx-mesa0 libegl1 \
    libvulkan1 libasound2t64 libx11-6 libxext6 libxcursor1 libxi6 libxfixes3 libxrandr2 \
    libxss1 libxtst6 xvfb x11-xkb-utils xkb-data 7zip unshield mawk \
    libdrm2 libgbm1 \
    gcc g++ make binutils libc6-dev libx11-dev libxtst-dev libsdl3-dev pkg-config \
    gcc-arm-linux-gnueabihf binutils-arm-linux-gnueabihf \
    cmake git wget ca-certificates xz-utils python3 \
    libpng-dev libncurses-dev

# ---------------------------------------------------------------------------
# 1b. Bibliotheques C armhf : Box86 est compile en ARM 32 bits (son dynarec
#     produit du code ARM 32 bits) ; le rootfs, lui, est arm64.  Le noyau
#     executera donc Box86 en mode AArch32 : il lui faut la libc armhf et son
#     chargeur.  Box86 emule ensuite lui-meme la libc i386 du jeu.
#     libc6-dev:armhf est indispensable pour lier (Scrt1.o, crti.o, ...) :
#     sans lui, la compilation croise echoue a l'edition de liens.
# ---------------------------------------------------------------------------
echo "=== libc armhf ==="
dpkg --add-architecture armhf
apt-get update -q
apt-get install -y -q --no-install-recommends \
    libc6:armhf libc6-dev:armhf libgcc-s1:armhf
ls -l /lib/ld-linux-armhf.so.3
ls -l /usr/lib/arm-linux-gnueabihf/crt1.o /usr/lib/arm-linux-gnueabihf/libc.so

# 2. Fichiers propres au runtime (run-swat4.sh, setup-wine.sh, asound.conf) et pont XTest
cp -r "$RUNTIME/rootfs-overlay/." /
chmod 755 /opt/ut/run-swat4.sh /opt/ut/setup-wine.sh

# Pont d'entree : injection X11 (touches -> Xvfb -> Wine)
gcc -O2 -shared -fPIC -Wall -o /opt/ut/xbridge "$RUNTIME/xbridge/xbridge.c" \
    -lX11 -lXtst

# 3. Mesa Turnip + Zink (GPU Adreno), compile par build-mesa.sh
if [ -f "$OUT/turnip.tgz" ]; then
    tar -C / -xzf "$OUT/turnip.tgz"
else
    echo "turnip.tgz absent : runtime sans acceleration GPU"
fi

# ---------------------------------------------------------------------------
# 4. Box86 : emulation x86 (32 bits) vers ARM64
# ---------------------------------------------------------------------------
echo "=== Box86 $BOX86_VERSION ==="
mkdir -p /tmp/box86
cd /tmp/box86
git clone --depth 1 --branch "$BOX86_VERSION" https://github.com/ptitSeb/box86.git src \
    || git clone --depth 1 https://github.com/ptitSeb/box86.git src
cd src
# Box86 se compile en ARM 32 bits (armhf) : son recompilateur dynamique
# (dynarec) produit du code ARM 32 bits, et son CMakeLists cherche donc le
# compilateur croise arm-linux-gnueabihf-gcc quand on cible ARM64.  Le
# binaire obtenu tourne en mode AArch32 sur un telephone arm64.
mkdir -p build && cd build
cmake -DCMAKE_BUILD_TYPE=Release -DARM64=ON -DLD80BITS=0 .. >/dev/null
make -j"$(nproc)" >/dev/null
install -m 755 box86 /opt/box86/box86
# La version standard de Box86 est un executable autonome (la variante BOX86LIB
# qui produit libbox86.so n'est pas utilisee) : rien d'autre a installer.
if [ ! -x /opt/box86/box86 ]; then
    echo "ECHEC binaire Box86 absent" >&2
    exit 3
fi
# Verification : Box86 doit etre un binaire ARM 32 bits (ELF32) avec le
# chargeur armhf.  Un binaire AArch64 ne serait pas valide ici.
if ! readelf -h /opt/box86/box86 2>/dev/null | grep -q 'Class:.*ELF32'; then
    echo "ECHEC Box86 n'est pas un binaire ELF32 (armhf)" >&2
    readelf -h /opt/box86/box86 >&2 || true
    exit 3
fi
readelf -l /opt/box86/box86 2>/dev/null | grep -A1 'INTERP' | head -n 3
echo "Box86 : $(du -h /opt/box86/box86 | cut -f1)"
cd /tmp && rm -rf /tmp/box86

# ---------------------------------------------------------------------------
# 5. Wine 32 bits (x86 pur) : le jeu est un binaire i386
#    (Box86 emule lui-meme libc.so.6 / libdl.so.2 / libpthread.so.0 pour le
#     programme invite : aucune glibc i386 n'est embarquee.)
# ---------------------------------------------------------------------------
echo "=== Wine $WINE_VERSION (x86) ==="
mkdir -p /opt/wine && cd /tmp
wget -q "https://github.com/Kron4ek/Wine-Builds/releases/download/$WINE_VERSION/wine-$WINE_VERSION-x86.tar.xz"
tar -C /tmp -xf "wine-$WINE_VERSION-x86.tar.xz"
rm -f "wine-$WINE_VERSION-x86.tar.xz"
# L'archive contient un dossier racine (wine-<version>-x86/) : on aplatit
# l'arborescence sous /opt/wine (bin/, lib/, share/).
WROOT=$(find /tmp -maxdepth 2 -type d -name 'wine-*-x86' | head -n 1)
if [ -z "$WROOT" ]; then
    echo "ECHEC archive Wine inattendue" >&2
    exit 3
fi
cp -a "$WROOT/." /opt/wine/
rm -rf /tmp/wine-*-x86
if [ ! -x /opt/wine/bin/wine ]; then
    echo "ECHEC binaire Wine 32 bits absent" >&2
    exit 3
fi
if [ ! -x /opt/wine/bin/wineserver ]; then
    echo "ECHEC wineserver absent" >&2
    exit 3
fi

# --- Elagage : l'arborescence livree contient 490 Mo, dont 136 Mo inutiles -
#   include/    : en-tetes de developpement (69 Mo)
#   *.a         : bibliotheques d'importation (64 Mo), seuls utiles pour
#                 compiler des applications Windows
#   share/man   : pages de manuel
#   share/wine/winmd : metadonnees .NET, inutiles ici
#   bin/*       : outils de compilation Wine (widl, wmc, wrc, winegcc...)
# On garde share/wine/nls (icudtl.dat + .nls), share/wine/fonts, wine.inf et
# l'integralite de lib/wine (i386-windows = DLL natives, i386-unix = modules).
rm -rf /opt/wine/include /opt/wine/share/man /opt/wine/share/wine/winmd
find /opt/wine -name '*.a' -delete
for t in widl wmc wrc winegcc winemaker winebuild winedump; do
    rm -f "/opt/wine/bin/$t"
done
echo "Wine elague : $(du -sh /opt/wine | cut -f1)"

# ---------------------------------------------------------------------------
# 7. Pilote Vulkan malisx2 (GPU Arm Mali)
#    Turnip ne parle qu'aux GPU Qualcomm Adreno.  Les GPU Arm Mali
#    (Helio G70 / Mali-G52 du Realme C25) utilisent le pilote noyau
#    mali_kbase expose par /dev/mali0 : malisx2 (Mesa PanVK) le pilote.
# ---------------------------------------------------------------------------
echo "=== malisx2 $MALISX2_VERSION (Arm Mali) ==="
mkdir -p /tmp/malisx2 && cd /tmp/malisx2
wget -q "https://github.com/bmdhacks/malisx2/releases/download/v$MALISX2_VERSION/malisx2-$MALISX2_VERSION-aarch64.tar.gz"
tar -xzf "malisx2-$MALISX2_VERSION-aarch64.tar.gz"
mkdir -p /opt/mali
cp "malisx2/$MALISX2_VERSION/libvulkan_malisx2.so" /opt/mali/
# Le manifeste livre designe le chemin d'installation d'origine
# (/storage/malisx2/<version>) : on ecrit le notre.
cat > /opt/mali/malisx2_icd.aarch64.json <<'EOF'
{
    "ICD": {
        "api_version": "1.3.0",
        "library_arch": "64",
        "library_path": "/opt/mali/libvulkan_malisx2.so"
    },
    "file_format_version": "1.0.1"
}
EOF
cp "malisx2/$MALISX2_VERSION/NOTICE.txt" /opt/mali/ 2>/dev/null || true
cd /tmp && rm -rf /tmp/malisx2
echo "malisx2 : $(du -h /opt/mali/libvulkan_malisx2.so | cut -f1)"

# ---------------------------------------------------------------------------
# 8. Rootfs minimal : uniquement les fichiers utilises (fermeture des dependances)
# ---------------------------------------------------------------------------
mkdir -p "$OUT"
sh "$RUNTIME/mkmin.sh" /tmp/rootfs
echo "nameserver 1.1.1.1" > /tmp/rootfs/etc/resolv.conf

# ---------------------------------------------------------------------------
# 9. Liste des paquets Debian redistribues (licences, sources : https://sources.debian.org)
# ---------------------------------------------------------------------------
(cd /tmp/rootfs && find . -type f -o -type l) | sed 's|^\.||' | grep -v '^/opt/' \
    | xargs dpkg -S 2>/dev/null | cut -d: -f1 | tr ',' '\n' | sed 's/ //g' | sort -u > /tmp/pkgs
mkdir -p /tmp/rootfs/usr/share/doc/openswat4
xargs dpkg-query -W -f '${Package} ${Version} (source : ${source:Package} ${source:Version})\n' \
    < /tmp/pkgs > /tmp/rootfs/usr/share/doc/openswat4/packages.txt
while read -r pkg; do
    [ -f "/usr/share/doc/$pkg/copyright" ] && \
        install -D -m 644 "/usr/share/doc/$pkg/copyright" "/tmp/rootfs/usr/share/doc/$pkg/copyright"
done < /tmp/pkgs
cp /tmp/rootfs/usr/share/doc/openswat4/packages.txt "$OUT/packages.txt"

# ---------------------------------------------------------------------------
# 10. Archive (.tgz et non .tar.gz : AGP decompresse les assets .gz)
# ---------------------------------------------------------------------------
tar -C /tmp/rootfs --numeric-owner -czf "$OUT/rootfs.tgz" .
chmod 644 "$OUT/rootfs.tgz"
ls -la "$OUT/rootfs.tgz"
