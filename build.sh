#!/bin/bash
# Локальная сборка «Кеши» внутри рабочей папки: JDK, Gradle и Android SDK
# лежат в /root/colesa.by/.toolchain и в систему не устанавливаются.
#
#   ./build.sh              -> assembleDebug (то же, что делает GitHub Actions)
#   ./build.sh release      -> assembleRelease (R8, пока без подписи)
#   ./build.sh clean        -> очистка
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export JAVA_HOME="$ROOT/../.toolchain/jdk"
export ANDROID_HOME="$ROOT/../.toolchain/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_USER_HOME="$ROOT/../.android-home"
export GRADLE_USER_HOME="$ROOT/../.gradle-home"
export PATH="$JAVA_HOME/bin:$PATH"

TASK="${1:-assembleDebug}"
if [ "$TASK" = "clean" ]; then
  TASK=clean
elif [ "$TASK" = "release" ]; then
  TASK=assembleRelease
elif [ "$TASK" = "debug" ]; then
  TASK=assembleDebug
fi

cd "$ROOT"
./gradlew "$TASK" --no-daemon --console=plain

if [ -d "$ROOT/app/build/outputs/apk" ]; then
  echo
  echo "=== Готовые APK ==="
  find "$ROOT/app/build/outputs/apk" -name "*.apk" -exec ls -lh {} \;
fi
