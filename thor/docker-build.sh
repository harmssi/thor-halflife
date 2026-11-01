#!/bin/bash
# Laeuft IM Container "thor-build" (siehe build.sh). /work = $THOR_BUILD_DIR auf dem Host.
#   1) SDL2 2.32.8                         -> /work/build/sdl2
#   2) hlsdk-portable (client + hl, arm64) -> /work/build/hlsdk
#   3) mainui_cpp (libmenu)                -> /work/build/mainui
#   4) Xash3D FWGS engine (waf)            -> /work/build/engine
#   5) thorbridge (JNI)                    -> /work/build/bridge
#   6) .so + SDL-Java + Assets stagen, Gradle assembleRelease -> /work/out/ThorHalfLife.apk
set -euo pipefail

W=/work
SRC=$W/src
NDK=${ANDROID_NDK_HOME:-/opt/android-sdk/ndk/27.2.12479018}
TC=$NDK/build/cmake/android.toolchain.cmake
ABI=arm64-v8a
API=26
SDL_VER=2.32.8
JOBS=$(nproc)
APP=$SRC/thor/android
STAGE=${THOR_STAGE:-all}
COMMON=(-G Ninja -DCMAKE_TOOLCHAIN_FILE=$TC -DANDROID_ABI=$ABI -DANDROID_PLATFORM=android-$API
        -DANDROID_NDK=$NDK -DCMAKE_BUILD_TYPE=Release)

mkdir -p $W/deps-src $W/deps-build $W/build $W/out

fetch() { # url datei
    [ -f "$W/deps-src/$2" ] || curl -fsSL -o "$W/deps-src/$2" "$1"
}
fetch https://github.com/libsdl-org/SDL/releases/download/release-$SDL_VER/SDL2-$SDL_VER.tar.gz SDL2-$SDL_VER.tar.gz
[ -d $W/deps-build/SDL2-$SDL_VER ] || tar xf $W/deps-src/SDL2-$SDL_VER.tar.gz -C $W/deps-build
SDL=$W/deps-build/SDL2-$SDL_VER

if [ "$STAGE" = all ] || [ "$STAGE" = native ]; then
echo "=== [1/6] SDL2 $SDL_VER"
if [ ! -f $W/build/sdl2/libSDL2.so ]; then
    cmake -S $SDL -B $W/build/sdl2 "${COMMON[@]}" \
        -DSDL_RENDER=OFF -DSDL_POWER=OFF -DSDL_VULKAN=OFF -DSDL_DISKAUDIO=OFF -DSDL_DUMMYAUDIO=OFF \
        -DSDL_DUMMYVIDEO=OFF -DSDL_OFFSCREEN=OFF -DSDL_STATIC=OFF -DSDL_TEST=OFF
    cmake --build $W/build/sdl2 -j$JOBS
fi

echo "=== [2/6] hlsdk-portable (client + server)"
cmake -S $SRC/3rdparty/hlsdk-portable -B $W/build/hlsdk "${COMMON[@]}" -DANDROID_APK=ON -D64BIT=ON
cmake --build $W/build/hlsdk -j$JOBS

echo "=== [3/6] mainui_cpp"
cmake -S $SRC/3rdparty/mainui -B $W/build/mainui "${COMMON[@]}" -DBUILD_AS_PART_OF_ENGINE=ON
cmake --build $W/build/mainui -j$JOBS

echo "=== [4/6] Xash3D FWGS"
cd $SRC
export ANDROID_NDK=$NDK BUILD_CMAKE_LIBRARY_OUTPUT_DIRECTORY=$W/build/sdl2 WAFLOCK=.lock-waf_thor
if [ ! -f $W/build/engine/c4che/_cache.py ]; then
    python3 waf configure -o $W/build/engine -T release --android=$ABI,,$API -s $SDL \
        --skip-sdl2-sanity-check --enable-bundled-deps
fi
python3 waf build -j$JOBS

echo "=== [5/6] thorbridge"
cmake -S $SRC/thor/native -B $W/build/bridge "${COMMON[@]}"
cmake --build $W/build/bridge -j$JOBS
fi

if [ "$STAGE" = all ] || [ "$STAGE" = apk ]; then
echo "=== [6/6] Stagen + Gradle"
JNI=$APP/app/src/main/jniLibs/$ABI
rm -rf $JNI $APP/app/src/main/java-sdl $APP/app/src/main/assets-xash
mkdir -p $JNI $APP/app/src/main/java-sdl $APP/app/src/main/assets-xash
cp $W/build/sdl2/libSDL2.so $JNI/
find $W/build/engine -name '*.so' -not -path '*/3rdparty/*' -exec cp {} $JNI/ \;
find $W/build/engine/3rdparty -name 'lib*.so' -exec cp {} $JNI/ \; 2>/dev/null || true
cp $(find $W/build/mainui -name 'libmenu.so' | head -1) $JNI/
cp $(find $W/build/hlsdk -name 'libclient*.so' | head -1) $JNI/
cp $(find $W/build/hlsdk -name 'libhl*.so' | head -1) $JNI/
cp $W/build/bridge/libthorbridge.so $JNI/
ls -la $JNI
cp -r $SDL/android-project/app/src/main/java/org $APP/app/src/main/java-sdl/
# Engine-Assets (Schriften, gamecontrollerdb, Gamepad-Presets) aus xash-extras -> assets-xash/ (Asset-Wurzel der Engine)
cp -r $SRC/3rdparty/extras/xash-extras/. $APP/app/src/main/assets-xash/
mkdir -p $W/keystore
if [ ! -f $W/keystore/thorhalflife.jks ]; then
    keytool -genkeypair -v -keystore $W/keystore/thorhalflife.jks -storepass thorhalflife -keypass thorhalflife \
        -alias thorhalflife -keyalg RSA -keysize 2048 -validity 20000 \
        -dname "CN=Thor Half-Life Debug, O=harmssi, C=DE"
fi
export THOR_KEYSTORE=$W/keystore/thorhalflife.jks
export GRADLE_USER_HOME=$W/gradle-home
echo "sdk.dir=${ANDROID_HOME:-/opt/android-sdk}" > $APP/local.properties
cd $APP
gradle --no-daemon -q assembleRelease ${THOR_GRADLE_ARGS:-}
cp $APP/app/build/outputs/apk/release/app-release.apk $W/out/ThorHalfLife.apk
ls -la $W/out/ThorHalfLife.apk
fi
