#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "$0")/../../.." && pwd)
sdk_path=$("$repo_root/apps/mobile/scripts/android-emulator.sh" sdk-path 2>/dev/null || true)
sdk_path=${sdk_path:-${ANDROID_HOME:-$HOME/Android/Sdk}}
export ANDROID_HOME="$sdk_path" ANDROID_SDK_ROOT="$sdk_path"
export PATH="$sdk_path/platform-tools:$sdk_path/cmdline-tools/latest/bin:$PATH"
mkdir -p "$repo_root/apps/mobile/android"
printf 'sdk.dir=%s\n' "$sdk_path" > "$repo_root/apps/mobile/android/local.properties"

current_java=$(java -version 2>&1 | awk -F '"' '/version/{print $2}' | cut -d. -f1)
if [[ "${current_java:-0}" -ge 24 ]]; then
  for candidate in "${JAVA_17_HOME:-}" "${JAVA_21_HOME:-}" "$HOME"/.sdkman/candidates/java/17* "$HOME"/.sdkman/candidates/java/21* /usr/lib/jvm/java-17* /usr/lib/jvm/java-21*; do
    if [[ -n "$candidate" && -d "$candidate" ]]; then
      export JAVA_HOME="$candidate"
      export PATH="$JAVA_HOME/bin:$PATH"
      break
    fi
  done
fi
cd "$repo_root/apps/mobile"
exec pnpm exec expo run:android --no-bundler
