#!/bin/bash
# Thor Half-Life bauen: Quellen zum Build-Host syncen, im Docker-Image "thor-build" bauen,
# APK nach dist/ThorHalfLife.apk holen. Optional: --install (adb install -r auf den Thor).
#   THOR_BUILD_HOST=user@linux-host-with-docker   (Pflicht)
#   ANDROID_SERIAL=<serial>                        (optional, fuer --install)
set -euo pipefail
HOST=${THOR_BUILD_HOST:?set THOR_BUILD_HOST=user@linux-host-with-docker}
REMOTE=${THOR_BUILD_DIR:-/mnt/cache/thor-halflife}
REPO="$(cd "$(dirname "$0")/.." && pwd)"

ssh "$HOST" "mkdir -p $REMOTE/src"
rsync -a --delete --exclude .git --exclude /build --exclude /dist --exclude '.lock-waf*' \
    --exclude thor/android/.gradle --exclude thor/android/app/build \
    --exclude thor/android/app/src/main/jniLibs --exclude thor/android/app/src/main/java-sdl \
    --exclude thor/android/app/src/main/assets-xash \
    "$REPO/" "$HOST:$REMOTE/src/"

ssh "$HOST" "docker run --rm -v $REMOTE:/work -e THOR_STAGE=\"${THOR_STAGE:-all}\" thor-build bash /work/src/thor/docker-build.sh"

mkdir -p "$REPO/dist"
scp "$HOST:$REMOTE/out/ThorHalfLife.apk" "$REPO/dist/ThorHalfLife.apk"
echo "APK: $REPO/dist/ThorHalfLife.apk"

if [ "${1:-}" = "--install" ]; then
    adb install -r "$REPO/dist/ThorHalfLife.apk"
fi
