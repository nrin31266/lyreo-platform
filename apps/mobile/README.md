# Lyreo Mobile

Expo SDK 57 + React Native 0.86 (New Architecture), Expo Development Client.

Mobile is the **learner experience**, providing dictation, shadowing, SRS vocabulary, TOEIC practice, and progress analytics.

---

## 1. Quick Start Guide

> [!IMPORTANT]
> **Expo Go is NOT supported.** Lyreo requires native audio recording and background execution via `expo-dev-client`. You must have the **Lyreo Development Build** installed on your device or emulator before Metro is useful.
> **Metro Port**: Metro runs on port **8082** by default (to avoid port 8081 occupied by Keycloak).

### Environment Configuration

```bash
# From repository root:
cp apps/mobile/.env.example apps/mobile/.env
```

Default addresses in `apps/mobile/.env`:
- **Android Emulator**: Reaches host services via `10.0.2.2` (Core: `10.0.2.2:8080`, Keycloak: `10.0.2.2:8081`).
- **Physical device**: Set `EXPO_PUBLIC_API_BASE_URL` and `EXPO_PUBLIC_KEYCLOAK_URL` to your machine's LAN IP, or use a development tunnel (`npx expo start --tunnel`).

All four `EXPO_PUBLIC_*` values in `.env.example` are required. Mobile validates them at startup;
they are public endpoint/client metadata and must never contain secrets.

---

## 2. Android Development (Linux CLI / No Android Studio needed)

### Prerequisites
- **JDK 17 LTS (canonical)** or 21 LTS for Android native compilation (matches EAS build image; Spring Boot 4 backend uses Java 25). `make mobile-android-install` automatically prioritizes JDK 17 when available.
- **Android Command-line Tools** (`adb`, `emulator`). If not installed yet:
  ```bash
  curl -fsSL https://dl.google.com/android/cli/latest/linux_x86_64/install.sh | bash
  ```
  Verify your environment:
  ```bash
  make android-check
  ```

### First-Time Android Setup (Run once)

```bash
# 1. Create the canonical emulator (Lyreo_Pixel8_API36)
make android-emulator-create

# 2. Start the emulator in a dedicated terminal (Terminal 1)
make android-emulator

# 3. Build & install the Lyreo Dev Build into the running emulator (Terminal 2)
make mobile-android-install

# 4. Start Metro bundler (Terminal 2)
make mobile
```

### Daily Android Workflow

1. **Terminal 1** (Start emulator):
   ```bash
   make android-emulator
   ```
2. **Terminal 2** (Start Metro):
   ```bash
   make mobile
   ```
   Press `a` in the Metro terminal or tap the **Lyreo** app on the emulator. Metro hot-reloads all TSX, components, and Tailwind styling instantly.

---

## 3. iOS Development (EAS Cloud / No macOS or Xcode needed on Linux)

### First-Time iOS Setup (Run once per physical iPhone)

```bash
# 1. Register your iPhone UDID (interactive EAS cloud flow)
make mobile-ios-device-register

# 2. Build the Development Build in EAS Cloud
make mobile-ios-build

# 3. Open the install URL printed by EAS in Safari on your iPhone and tap "Install"

# 4. Start Metro bundler on your PC
make mobile
```

### Daily iOS Workflow

1. Start Metro on your PC:
   ```bash
   make mobile
   ```
2. Open the **Lyreo** app on your iPhone (ensure your phone is on the same Wi-Fi network).

---

## 4. When to Rebuild vs When Metro Hot-Reloads

| Change Type | Action Needed |
|---|---|
| Editing `.tsx` screens, components, UI, styles | **Hot-reload** (`make mobile` is enough) |
| Updating translation strings in `@lyreo/i18n` | **Hot-reload** (`make mobile` is enough) |
| Changing `EXPO_PUBLIC_*` in `.env` | Restart Metro (`make mobile`) |
| Adding/removing npm packages with native code | Rebuild (`make mobile-android-install` or `make mobile-ios-build`) |
| Modifying Expo config plugins in `app.json` | Rebuild native app |
| Upgrading Expo SDK version | Rebuild native app |

---

## 5. Architecture & Native Code Policy

- **Continuous Native Generation (CNG)**: `apps/mobile/android/` and `apps/mobile/ios/` are **generated build artifacts** derived from `app.json` and `package.json`. They are intentionally git-ignored. Do not commit manual edits inside those folders; declare native changes via Expo config plugins instead.
- **UI Foundation**: Uses NativeWind v4 (Tailwind v3.4), consuming semantic light/dark tokens from `@lyreo/design-system`.
- **Internationalization**: Uses `@lyreo/i18n` for shared EN/VI resources.
- **Navigation**: Expo Router route groups separate public and authenticated screens. Root
  `Stack.Protected` guards derive only from `SessionProvider` status.
- **Security & Storage**: The access token stays in memory. Refresh and ID token material use
  `expo-secure-store`; a stored refresh token is validated through OIDC during app bootstrap.
  Ordinary preferences (theme, locale) use `AsyncStorage`.
- **API**: Feature code uses the shared authenticated client in `src/api`. It refreshes once and
  retries once on `401`, then invalidates the session. Core RFC 9457 errors are normalized with
  their stable code, field violations and correlation ID.
- **OIDC callback**: The Development Build returns to `lyreo://auth/callback`. Run
  `make keycloak-seed` after pulling callback configuration changes so an existing realm is updated.
- **Typecheck**:
  ```bash
  pnpm --filter @lyreo/mobile typecheck
  ```
- **Foundation tests**:
  ```bash
  pnpm --filter @lyreo/mobile test
  ```

---

## 6. Common Troubleshooting

| Issue | Cause & Solution |
|---|---|
| **`/dev/kvm: Permission denied`** | Add your user to the KVM group: `sudo usermod -aG kvm $(whoami)`, then log out and back in. |
| **Port 8081 already in use** | Keycloak runs on 8081. Mobile Metro runs on port **8082** by default to avoid conflicts. `make mobile` automatically uses 8082. |
| **Android native build fails on Java 25** | Android Gradle Plugin (AGP) requires JDK 17 (or 21 LTS). Install JDK 17 via SDKMAN (`sdk install java 17.0.14-tem`). `make mobile-android-install` automatically prioritizes JDK 17. |
| **Emulator cannot reach backend services** | In the Android emulator, `localhost` refers to the emulator itself. Use `10.0.2.2` to access host services (e.g. `http://10.0.2.2:8080` for Core). |
