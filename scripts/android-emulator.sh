#!/usr/bin/env bash
# scripts/android-emulator.sh
#
# Android AVD helper for Lyreo development on Linux.
# Manages the canonical Lyreo_Pixel8_API36 emulator without Android Studio.
#
# Primary interface: Modern Android CLI (`android sdk ...`, `android emulator ...`)
# Supported fallback: Android Command-line Tools (`sdkmanager`, `avdmanager`)
#
# Usage:
#   ./scripts/android-emulator.sh check
#   ./scripts/android-emulator.sh create
#   ./scripts/android-emulator.sh start
#   ./scripts/android-emulator.sh list
#
# Requires Android SDK installed and accessible via ANDROID_HOME, ANDROID_SDK_ROOT,
# $HOME/Android/Sdk, or the `android` CLI.
# Does NOT require Android Studio.

set -euo pipefail

# ── Constants ──────────────────────────────────────────────────────────────────

AVD_NAME="Lyreo_Pixel8_API36"
API_LEVEL="36"
ABI="x86_64"
# Package identifiers:
# Modern Android CLI: system-images/android-36/google_apis/x86_64
# Legacy SDKManager:   system-images;android-36;google_apis;x86_64
SYSTEM_IMAGE_PKG="system-images/android-${API_LEVEL}/google_apis/${ABI}"
LEGACY_SYSTEM_IMAGE="system-images;android-${API_LEVEL};google_apis;${ABI}"
DEVICE_PROFILE="pixel_8"

# ── SDK detection ──────────────────────────────────────────────────────────────

detect_sdk() {
  # 1. Ask the modern `android` CLI if available
  if command -v android >/dev/null 2>&1; then
    local cli_sdk
    cli_sdk="$(android info 2>/dev/null | awk '/^sdk:/{print $2}' || true)"
    if [ -n "${cli_sdk}" ] && [ -d "${cli_sdk}" ]; then
      echo "${cli_sdk}"
      return 0
    fi
  fi

  # 2. Environment variables
  if [ -n "${ANDROID_HOME:-}" ] && [ -d "${ANDROID_HOME}" ]; then
    echo "${ANDROID_HOME}"
    return 0
  fi
  if [ -n "${ANDROID_SDK_ROOT:-}" ] && [ -d "${ANDROID_SDK_ROOT}" ]; then
    echo "${ANDROID_SDK_ROOT}"
    return 0
  fi

  # 3. Standard Linux user location
  if [ -d "${HOME}/Android/Sdk" ]; then
    echo "${HOME}/Android/Sdk"
    return 0
  fi
  return 1
}

require_sdk() {
  if ! SDK_ROOT="$(detect_sdk)"; then
    echo "ERROR: Android SDK not found." >&2
    echo "" >&2
    echo "Set ANDROID_HOME to your SDK root, e.g.:" >&2
    echo "  export ANDROID_HOME=\$HOME/Android/Sdk" >&2
    echo "" >&2
    echo "Or install the Android CLI via:" >&2
    echo "  curl -fsSL https://dl.google.com/android/cli/latest/linux_x86_64/install.sh | bash" >&2
    echo "and follow docs/DEVELOPMENT.md for setup instructions." >&2
    exit 1
  fi
  export ANDROID_HOME="${SDK_ROOT}"
  export ANDROID_SDK_ROOT="${SDK_ROOT}"
}

setup_path() {
  require_sdk
  # Ensure local bin and all SDK tool directories are in PATH for this process
  export PATH="${HOME}/.local/bin:${SDK_ROOT}/cmdline-tools/latest/bin:${SDK_ROOT}/cmdline-tools/bin:${SDK_ROOT}/tools/bin:${SDK_ROOT}/tools:${SDK_ROOT}/emulator:${SDK_ROOT}/platform-tools:${PATH}"
  # Linux compatibility workaround scoped to emulator process (prevents Mesa device select layer crash in QEMU)
  export DISABLE_MESA_DEVICE_SELECT=1
}

# ── KVM check ─────────────────────────────────────────────────────────────────

check_kvm() {
  if [ "$(uname -s)" != "Linux" ]; then
    return 0  # non-Linux hosts manage virtualization differently
  fi
  if [ ! -e /dev/kvm ]; then
    echo "WARNING: /dev/kvm not found. Hardware acceleration (KVM) is unavailable." >&2
    echo "  The emulator will run but will be significantly slower." >&2
    echo "  To enable KVM, ensure your CPU supports virtualization and it is" >&2
    echo "  enabled in BIOS/UEFI, then run: sudo modprobe kvm_intel  (or kvm_amd)" >&2
    return 0
  fi
  if [ ! -r /dev/kvm ] || [ ! -w /dev/kvm ]; then
    echo "WARNING: /dev/kvm exists but current user lacks read/write access." >&2
    echo "  Add your user to the kvm group (no sudo needed after that):" >&2
    echo "    sudo usermod -aG kvm \$(whoami)" >&2
    echo "    # then log out and back in" >&2
    return 0
  fi
  echo "  KVM: /dev/kvm accessible — hardware acceleration enabled."
}

# ── Helper: check if AVD exists ────────────────────────────────────────────────

avd_exists() {
  local name="$1"
  if command -v android >/dev/null 2>&1; then
    if android emulator list 2>/dev/null | grep -qx "${name}"; then
      return 0
    fi
  fi
  if command -v avdmanager >/dev/null 2>&1; then
    if avdmanager list avd -c 2>/dev/null | grep -qx "${name}"; then
      return 0
    fi
  fi
  if command -v emulator >/dev/null 2>&1; then
    if emulator -list-avds 2>/dev/null | grep -qx "${name}"; then
      return 0
    fi
  fi
  if [ -f "${HOME}/.android/avd/${name}.ini" ]; then
    return 0
  fi
  return 1
}

# ── Commands ───────────────────────────────────────────────────────────────────

cmd_check() {
  echo "=== Android environment check ==="
  echo ""

  setup_path
  echo "  SDK root: ${SDK_ROOT}"

  # Android CLI (modern unified tool)
  if command -v android >/dev/null 2>&1; then
    local cli_ver
    cli_ver="$(android info 2>/dev/null | awk '/^version:/{print $2}' || true)"
    echo "  android CLI : found (${cli_ver:-available})"
  else
    echo "  android CLI : NOT FOUND (recommended: curl -fsSL https://dl.google.com/android/cli/latest/linux_x86_64/install.sh | bash)"
  fi

  # adb
  if command -v adb >/dev/null 2>&1; then
    echo "  adb         : $(adb version 2>&1 | head -1)"
  else
    echo "  adb         : NOT FOUND (install platform-tools)"
  fi

  # emulator
  if command -v emulator >/dev/null 2>&1; then
    echo "  emulator    : $(emulator -version 2>&1 | head -1)"
  else
    echo "  emulator    : NOT FOUND (install emulator package)"
  fi

  # cmdline-tools (avdmanager / sdkmanager)
  if command -v avdmanager >/dev/null 2>&1; then
    echo "  avdmanager  : found"
  else
    echo "  avdmanager  : NOT FOUND (install cmdline-tools;latest)"
  fi

  echo ""
  check_kvm
  echo ""

  # List existing AVDs
  echo "  Existing AVDs:"
  cmd_list | sed 's/^/    /'
  echo ""
  if avd_exists "${AVD_NAME}"; then
    echo "  Canonical AVD '${AVD_NAME}': EXISTS"
  else
    echo "  Canonical AVD '${AVD_NAME}': not created yet (run: make android-emulator-create)"
  fi

  echo ""
  echo "Check complete."
}

cmd_create() {
  setup_path

  # 1. Idempotency: skip if AVD already exists
  if avd_exists "${AVD_NAME}"; then
    echo "AVD '${AVD_NAME}' already exists. Nothing to do."
    echo "To start it: make android-emulator"
    exit 0
  fi

  # 2. Ensure system image is installed
  local image_dir="${SDK_ROOT}/system-images/android-${API_LEVEL}/google_apis/${ABI}"
  echo "Checking system image: ${SYSTEM_IMAGE_PKG}"
  if [ ! -d "${image_dir}" ]; then
    echo "Installing system image (this may take a while)..."
    if command -v android >/dev/null 2>&1; then
      # Modern Android CLI
      android sdk install "${SYSTEM_IMAGE_PKG}"
    elif command -v sdkmanager >/dev/null 2>&1; then
      # Legacy sdkmanager fallback
      yes | sdkmanager "${LEGACY_SYSTEM_IMAGE}"
    else
      echo "ERROR: Neither 'android' CLI nor 'sdkmanager' found to install system image." >&2
      exit 1
    fi
  else
    echo "System image already installed at ${image_dir}."
  fi

  # 3. Create canonical AVD
  # NOTE ON TOOL CHOICE:
  # `android emulator create` in Android CLI 1.0 only accepts generic templates
  # (e.g. medium_phone) and hardcodes `google_apis_playstore`. It does not support
  # custom naming (--name), package specification, or device profiles (pixel_8).
  # Therefore, `avdmanager create avd` is used here to ensure the exact canonical
  # name 'Lyreo_Pixel8_API36', device 'pixel_8', and 'google_apis' image are configured.
  echo "Creating canonical AVD: ${AVD_NAME}"
  if ! command -v avdmanager >/dev/null 2>&1; then
    echo "ERROR: avdmanager is required for canonical AVD creation." >&2
    echo "Install cmdline-tools via: android sdk install cmdline-tools;latest" >&2
    exit 1
  fi

  echo "no" | avdmanager create avd \
    --name "${AVD_NAME}" \
    --package "${LEGACY_SYSTEM_IMAGE}" \
    --device "${DEVICE_PROFILE}" \
    --force 2>/dev/null || {
    if ! avd_exists "${AVD_NAME}"; then
      echo "ERROR: AVD creation failed." >&2
      exit 1
    fi
  }

  echo ""
  echo "AVD '${AVD_NAME}' created successfully."
  echo "  API level : Android ${API_LEVEL}"
  echo "  ABI       : ${ABI}"
  echo "  Image     : google_apis"
  echo "  Device    : ${DEVICE_PROFILE}"
  echo ""
  echo "Start it with: make android-emulator"
}

cmd_start() {
  setup_path

  if ! avd_exists "${AVD_NAME}"; then
    echo "ERROR: AVD '${AVD_NAME}' does not exist." >&2
    echo "  Create it first: make android-emulator-create" >&2
    exit 1
  fi

  check_kvm
  local gpu_mode="${ANDROID_EMULATOR_GPU:-auto}"
  echo ""
  echo "Starting emulator: ${AVD_NAME}"
  echo "  Graphics : ${gpu_mode} (override with ANDROID_EMULATOR_GPU=software if needed)"
  echo "  KVM      : enabled when /dev/kvm is accessible"
  echo "  Press Ctrl-C to stop."
  echo ""

  # NOTE ON TOOL CHOICE:
  # We invoke `emulator` binary directly with configurable GPU mode instead of
  # `android emulator start`. The `android emulator start` command currently
  # only exposes `--cold` and defaults to host GPU rendering without renderer flags.
  # Passing `-gpu ${gpu_mode}`, `-no-snapshot`, and `-no-boot-anim` to the
  # emulator binary provides optimal graphics on host GPU with safe software fallback
  # across Linux distributions (Fedora, Ubuntu, Arch, Debian).
  if ! command -v emulator >/dev/null 2>&1; then
    echo "ERROR: emulator binary not found in PATH or SDK." >&2
    exit 1
  fi

  exec emulator \
    -avd "${AVD_NAME}" \
    -gpu "${gpu_mode}" \
    -no-snapshot \
    -no-boot-anim
}

cmd_list() {
  setup_path
  if command -v android >/dev/null 2>&1; then
    android emulator list 2>/dev/null || echo "(none)"
  elif command -v avdmanager >/dev/null 2>&1; then
    avdmanager list avd -c 2>/dev/null || echo "(none)"
  elif command -v emulator >/dev/null 2>&1; then
    emulator -list-avds 2>/dev/null || echo "(none)"
  else
    echo "(tools not found to list AVDs)"
  fi
}

cmd_sdk_path() {
  detect_sdk || echo "${HOME}/Android/Sdk"
}

# ── Dispatch ───────────────────────────────────────────────────────────────────

COMMAND="${1:-help}"

case "${COMMAND}" in
  check)    cmd_check   ;;
  create)   cmd_create  ;;
  start)    cmd_start   ;;
  list)     cmd_list    ;;
  sdk-path) cmd_sdk_path ;;
  help|--help|-h)
    echo "Usage: $0 <command>"
    echo ""
    echo "Commands:"
    echo "  check   Verify Android CLI tools, KVM, and AVD status"
    echo "  create  Create the canonical Lyreo AVD (idempotent)"
    echo "  start   Start the canonical Lyreo emulator (software GPU, no host GPU needed)"
    echo "  list    List all available AVDs"
    ;;
  *)
    echo "ERROR: Unknown command '${COMMAND}'" >&2
    echo "Run '$0 help' for usage." >&2
    exit 1
    ;;
esac
