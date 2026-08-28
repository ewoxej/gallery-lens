# Gallery Lens

On-device photo text search for Android — like iCloud Photos' text search, but
offline. Gallery Lens indexes the text in your gallery photos with OCR and lets
you find any photo by the words on it (receipts, signs, screenshots, documents).

## Features

- **Offline OCR** — hybrid engine: [ML Kit](https://developers.google.com/ml-kit/vision/text-recognition/v2)
  for Latin script + [Tesseract](https://github.com/adaptech-cz/Tesseract4Android)
  (`rus`+`eng`) for Cyrillic, which ML Kit has no model for.
- **Full-text search** — Room + FTS4 with the `unicode61` tokenizer (Cyrillic
  case folding, `ё`/`е` normalisation) and prefix matching.
- **Auto-indexing** — a WorkManager content-URI trigger picks up new photos in
  the background, even when the app is closed.
- **Gallery viewer** — full-screen swipeable pager, a date scrubber on the grid,
  and a text overlay that boxes each recognised line and copies it on tap.

## Build

```sh
./gradlew :app:assembleDebug      # debug APK
./gradlew :app:assembleRelease    # release APKs (R8, per-ABI splits + universal)
```

## Structure

- `data/` — Room entities, FTS table, DAO, search repository.
- `ocr/` — the hybrid OCR engine + result/layout model + tessdata installer.
- `work/` — MediaStore scanner, indexing worker, background content-trigger watcher.
- `ui/` — search grid + date scrubber, full-screen pager, settings.
