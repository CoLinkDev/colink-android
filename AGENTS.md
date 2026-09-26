# CoLink Android Agent Notes

## Protocol Versions

- `.colink/protocol/version.yml` records the existing Business and P2P protocol versions with which this project is currently aligned.
- When the implementation changes to align with a different published protocol version, update the corresponding value in this file in the same change.

## Build Variants

| Configuration | Release Variant | Debug Variant |
| :--- | :--- | :--- |
| **Application ID (Package Name)** | `com.colink.android` | `com.colink.android.debug` |
| **Application Name (App Label)** | `CoLink` | `CoLink Debug` |

Unless specified otherwise by the user, build and run actions use the debug variant.

## Dialog Design & Visual Standards

- Use Material 3 `AlertDialog` for confirmation dialogs.
- Put the dialog icon in the centered `icon` slot. Tint destructive actions with
  `MaterialTheme.colorScheme.error`; tint regular actions with
  `MaterialTheme.colorScheme.primary`.
- Use a filled `Button` for `confirmButton` and a `TextButton` for `dismissButton`.
  For destructive confirmation buttons, use `error` as the container color and
  `onError` as the content color.
- Keep titles concise and render them directly as `Text(title)`. Do not embed icons
  or extra layout containers in the title slot.


## Release Tags

Release tags MUST be annotated tags (`git tag -a v1.25.0 -m "Release v1.25.0"`), not lightweight tags.

## Version Management

Git tag is the version source of truth. CI extracts `VERSION_NAME` and derives `VERSION_CODE` (formula: `major*10000 + minor*100 + patch`, e.g., `v1.25.0` → `12500`) from the tag during release builds. Do not manually modify `versionCode` or `versionName` in `app/build.gradle.kts`.
