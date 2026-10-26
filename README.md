# Photo Finder

A privacy-first native Android photo organizer, built with Kotlin and Jetpack Compose.

## First milestone

- Native Android app for Android 8.0+.
- Requests permission before reading the gallery.
- Shows images from the device's MediaStore gallery.
- Searches filenames and locally indexed screenshot text.
- Uses on-device Google ML Kit text recognition to index text inside screenshots whose filenames include "screenshot", "screen_shot", or "screen shot".
- Saves OCR text locally on the device so it can be searched after restarting the app.
- Keeps image processing on-device; no photo uploads or account required.
- Builds a release APK with GitHub Actions.

## Build

Push to `main`, or manually run **Android Release APK** from the Actions tab. Download the artifact named `photo-finder-release-apk` from a successful workflow run.

The APK is an unsigned release build intended for direct testing. Google Play publishing would require signing and store preparation.

## Current limitations

- Screenshot indexing currently scans up to 250 screenshots identified by filename.
- Text search works for screenshots after indexing them.
- Visual semantic search, duplicate review, and safe cleanup are planned next.
