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
