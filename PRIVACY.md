# Privacy

Zenit has no accounts, analytics, ads or tracking.

## Location

Zenit reads your location from the phone's GNSS receiver only to show it on screen and to work out which satellites are above you. Your location is **never sent anywhere**. It stays on the device: the most recent fix is saved locally so the screen can be restored after the app restarts. Zenit does not use background location.

## Sensors

Compass, motion and pressure readings are processed on the device and never leave it.

## Network

Zenit uses the network for only two things:

1. **Orbital data.** About once a day it downloads public orbital elements from CelesTrak (`celestrak.org`). The requests contain no location or personal data.
2. **Update check.** It asks GitHub (`api.github.com`) for the latest release of this app and, if you choose to update, downloads the APK from GitHub. The requests contain only the app version in the `User-Agent` header.

These services may log your IP address under their own privacy policies, like any web request.

## Permissions

See the [README](README.md#permissions) for each permission and why it's needed.

## Contact

Open an issue at <https://github.com/divanguz-alt/zenit/issues>.
