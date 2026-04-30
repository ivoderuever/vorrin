# Vorrin

Vorrin is a native audiobook listening app for Android, specifically designed for a seamless experience with `.m4b` files.

## Features

- **Native Performance**: Built with Kotlin and modern Android components for a fast and responsive experience.
- **Modern UI**: Leveraging Jetpack Compose and Material 3 (Expressive) for a clean, intuitive, and beautiful interface.
- **Progress Tracking**: Keep track of your reading progress with visual indicators and badges for finished books.
- **M4B Support**: Optimized for the audiobook format you already use.

## Tech Stack

- **UI**: [Jetpack Compose](https://developer.android.com/jetpack/compose)
- **Material Design**: [Material 3](https://m3.material.io/)
- **Media Playback**: [Media3 (ExoPlayer)](https://developer.android.com/guide/topics/media/media3)
- **Image Loading**: [Coil](https://coil-kt.github.io/coil/)
- **Data Persistence**: [DataStore](https://developer.android.com/topic/libraries/architecture/datastore)

## Getting Started

### Prerequisites

- Android Studio Ladybug or newer.
- Android SDK 35 (Target SDK).
- A device or emulator running Android 8.0 (API level 26) or higher.

### Installation

1. Clone the repository:
   ```bash
   git clone https://github.com/deruever/vorrin.git
   ```
2. Open the project in Android Studio.
3. Sync the project with Gradle files.
4. Run the app on your device or emulator.

## Project Structure

- `app/src/main/java/nl/deruever/vorrin/ui`: Contains the Compose screens and components.
    - `library`: The main library view showing your collection.
    - `player`: The playback interface (Work in progress).
    - `theme`: App-wide styling and Material 3 theme configuration.
- `app/src/main/java/nl/deruever/vorrin/data`: Data models and repositories.

## License

This project is currently unlicensed.
