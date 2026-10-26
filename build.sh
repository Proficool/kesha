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

# Всегда собираем начисто. Инкрементальная сборка уже один раз выдала испорченный APK:
# при смене числа dex-файлов в build/ оставался старый classes3.dex, и упаковщик писал
# записи в архиве внахлёст — центральный каталог перестал соответствовать заголовкам.
# Внешне файл выглядел нормально и отличался только размером. Дешевле собрать начисто,
# чем ловить такое на телефоне.
if [ "$TASK" != "clean" ]; then
  ./gradlew clean --no-daemon --console=plain -q
fi

./gradlew "$TASK" --no-daemon --console=plain

# Проверка целостности архива: перекрывающиеся записи и битые контрольные суммы.
if [ -f "$ROOT/app/build/outputs/apk/debug/app-debug.apk" ]; then
  python3 - "$ROOT/app/build/outputs/apk/debug/app-debug.apk" <<'PY'
import sys, zipfile
path = sys.argv[1]
z = zipfile.ZipFile(path)
items = sorted(z.infolist(), key=lambda i: i.header_offset)
bad = []
for a, b in zip(items, items[1:]):
    end = a.header_offset + 30 + len(a.filename.encode()) + len(a.extra) + a.compress_size
    if end > b.header_offset:
        bad.append(f"{a.filename} -> {b.filename}")
if bad or z.testzip() is not None:
    sys.exit(f"APK ПОВРЕЖДЁН: перекрытия={bad}, testzip={z.testzip()}")
print(f"архив цел: {len(items)} записей, перекрытий нет")
PY
fi

if [ -d "$ROOT/app/build/outputs/apk" ]; then
  echo
  echo "=== Готовые APK ==="
  find "$ROOT/app/build/outputs/apk" -name "*.apk" -exec ls -lh {} \;
fi
