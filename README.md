<p align="center">
  <img src="vorrin-logo.svg" alt="Vorrin logo" width="120"/>
</p>

<h1 align="center">Vorrin</h1>

<p align="center">A native audiobook player for Android, built for <code>.m4b</code> files.</p>

---

Vorrin is a modern Material 3 (Expressive) audiobook app. Build to replace old looking apps which have basic features behind a paywall. Pick a folder of `.m4b` files to start listening.

## Features

- **Correct metadata** — Displays the current book, author, chapter, and per chapter/track progress in the app and on connected bluetooth devices.
- **Modern UI** — Build with Material 3 (Expressive) and fully adapts to your system color scheme.
- **Playback speed** — Adjust the playback speed from the player screen.
- **Configurable skip duration** — Set your preferred skip forward/backward duration from the player screen.
- **See chapters or tracks** — Jump to any chapter or track in the book from the player screen or see their duration.
- **Background playback** — Full background playback support with a persistent media notification with custom controls.
- **Keeps track of where you left off** — Your progress is saved automatically so you can pick up exactly where you left off.

## Tech Stack

| Layer | Library |
|---|---|
| UI | [Jetpack Compose](https://developer.android.com/jetpack/compose) |
| Design system | [Material 3](https://m3.material.io/) |
| Media playback | [Media3 (ExoPlayer)](https://developer.android.com/guide/topics/media/media3) |
| Local database | [Room](https://developer.android.com/training/data-storage/room) |
| Preferences | [DataStore](https://developer.android.com/topic/libraries/architecture/datastore) |
| Image loading | [Coil](https://coil-kt.github.io/coil/) |

## Getting Started

### Prerequisites

- Android Studio Meerkat or newer
- Android SDK 36 (Target SDK)
- A device or emulator running Android 12 (API level 31) or higher

### Installation

1. Clone the repository:
   ```bash
   git clone git@github.com:ivoderuever/vorrin.git
   ```
2. Open the project in Android Studio.
3. Sync the project with Gradle files.
4. Run the app on your device or emulator.

## Project Structure

```
app/src/main/java/nl/deruever/vorrin/
├── ui/
│   ├── library/        # Library screen — book grid with progress indicators
│   ├── player/         # Player screen — controls, chapters, speed, skip duration
│   ├── components/     # Shared UI components (e.g. folder picker)
│   ├── navigation/     # Navigation routes
│   └── theme/          # Material 3 theme, colors, and typography
├── data/
│   ├── db/             # Room database, entities, and DAOs
│   ├── Audiobook.kt    # Core data model
│   ├── BookStatus.kt   # UNREAD / IN_PROGRESS / FINISHED
│   ├── BookRepository.kt
│   └── PreferencesRepository.kt
└── service/
    └── AudiobookService.kt  # Foreground service for background playback
```

## License

This project is currently unlicensed.
