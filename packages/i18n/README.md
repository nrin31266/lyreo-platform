# @lyreo/i18n

Shared locale metadata and translation resources for Lyreo frontends.

Resources are split by ownership:

```text
common   → truly shared UI copy
admin    → Admin Web only
mobile   → learner Mobile only
```

The package intentionally does **not** initialize i18next. Each application owns its platform
adapter, locale detection, and persistence:

- Admin Web: browser locale + `localStorage`;
- Mobile: `expo-localization` + AsyncStorage.

This avoids coupling Web and React Native lifecycle/storage behavior while keeping translation keys
and shared copy in one place.
