# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
[Semantic Versioning](https://semver.org/).

## [1.2.2] - 2026-10-22

### Added
- Tagline under the Settings header: "Satellites, live on the globe."

## [1.2.1] - 2026-10-22

### Fixed
- "Check for updates" always asks GitHub instead of reusing an answer cached for up to an hour, so a release published minutes ago shows up straight away. Without a network connection it falls back to the last known answer.

## [1.2.0] - 2026-10-22

### Changed
- New app icon ("Quarter Earth"): a satellite sending signal waves to your location. Adaptive, round and Material You themed versions.

### Added
- Splash screen with the new icon.
- App icon next to the name and version in Settings → About.

## [1.1.0] - 2026-10-22

### Added
- Drag the globe to rotate it, with momentum. The camera stops following your position while you look around; tap the fix chip (⌖) to fly back.

### Removed
- Step counter and the physical-activity permission it needed.

## [1.0.0] - 2026-10-21

### Added
- Live GNSS dashboard: position (DDM/DMS/DD), last fix, speed, MSL altitude (AltitudeConverter on Android 14+, NMEA GGA before that), accuracy, HDOP/PDOP/VDOP and fix type from NMEA GSA.
- Satellite status for GPS, Galileo, GLONASS, BeiDou, QZSS, SBAS and NavIC, with a C/N0 bar chart and per-constellation used/visible counts.
- Real satellite positions from CelesTrak OMM elements with a Kotlin SGP4/SDP4 port (verified against Vallado's test vectors), matched to receiver reports by PRN or geometry.
- OpenGL ES 3.0 globe in Illustrated (dot-matrix land) and Realistic (Blue Marble) styles, with an optional real day/night terminator and city lights.
- Search, lock, satellite-reveal and signal-wave animations; the system "Remove animations" setting is respected.
- ISS easter egg at its real position, with orbit, trail and an optional time-lapse.
- Sensor cards: heading, orientation, pitch/roll, rotation, acceleration, magnetic field and declination, pressure with trend, and steps today.
- Settings for units, display options and globe style, plus orbital data refresh, licenses and a GitHub link.
- In-app update check against GitHub Releases, with SHA-256 and signing-certificate verification.
- Daily background refresh of orbital data with WorkManager, and offline operation from the cache.

## [0.0.1] - 2026-10-21

### Added
- Project scaffold and release pipeline test build.
