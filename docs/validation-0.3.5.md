# Android 0.3.5 validation

Import now reads the package's video filename, searches readable persisted document grants and granted folders (including subfolders), opens the matching video and performs the existing full-content hash verification before saving its subtitles. Import is available without an already open video. If access is missing, the dialog names the required file and offers a folder grant or a single video picker. Android cannot search files outside granted locations. Renamed videos can be selected manually; same-name files with different bytes are still rejected.

Content-provider video descriptors now open on Dispatchers.IO. The player shows a loading message, disables playback until ready, and lets the rest of the app remain interactive. Disposal cancels loading and closes late-arriving descriptors without accessing a released player.

Validation on the API 36 emulator:

- Existing 0.3.4 fails the new cloud startup test: 14,789 ms with a provider that blocks for 12 seconds.
- Patched build passes cloud startup and episode lookup through a persisted folder grant, including a nested directory and a missing episode.
- Seven Android startup/import/storage tests pass in 3.199 seconds.
- Six JVM playback synchronization tests pass.
- Synthetic video playback remains visible after entering/exiting fullscreen in a 1920x1200 layout.
- Additional Whisper smoke test aborts in libomp under ARM64 translation on the x86_64 emulator. All ten native libraries have identical hashes to 0.3.4; native transcription was not validated by this release. No physical tablet was attached.
