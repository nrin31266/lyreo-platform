# Lyreo Mobile

Expo SDK 57 + React Native 0.86, Development Build/Prebuild, New Architecture.

Mobile là **learner experience**, không phải admin console. Phase đầu app yêu cầu login trước khi vào learning state để progress/SRS/Curriculum/Diamond luôn có owner rõ ràng.

## Main learner areas

```text
Home / Continue learning
Lesson (Dictation / Shadowing / contextual notes)
Vocabulary SRS
TOEIC
Progress
Settings
```

Chat được giữ boundary nhưng ưu tiên thấp.

## Why Development Build

Lyreo cần audio playback/recording và có khả năng thêm native capability sau này. Không thiết kế app production quanh Expo Go-only.

## Environment

```bash
cp .env.example .env
```

`EXPO_PUBLIC_*` là public metadata. Không đặt API key/client secret.

Android Emulator mặc định dùng `10.0.2.2` để gọi host machine. Physical device phải dùng IP LAN/tunnel phù hợp.

## Authentication

Authorization Code + PKCE với public Keycloak client `lyreo-mobile`. Token/session secret lưu qua `expo-secure-store`, không AsyncStorage plaintext.

## Run

```bash
pnpm --filter @lyreo/mobile start
pnpm --filter @lyreo/mobile android
```

Sau khi thêm native module hoặc đổi SDK, rebuild Development Build.

## Product principle

Learning screen ưu tiên focus và feedback hiện đại; không nhồi mọi annotation cùng lúc. Learner preference quyết định `OFF / TAP_TO_SHOW / AFTER_ATTEMPT / ALWAYS` cho IPA/translation/notes khi admin policy cho phép.
