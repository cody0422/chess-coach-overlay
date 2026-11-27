# ChessCoach Overlay (Android)

100% offline Android floating chess coach overlay (`SYSTEM_ALERT_WINDOW`) built in Kotlin with zero API calls and unlimited moves per day.

## Features
- **Floating Dark Pill Bar** (draggable): Play/Analyze, live `Elo · Accuracy` readout, Align Board, Tap-Move / Pass-through toggle (`FLAG_NOT_TOUCHABLE`), Flip (W/B), Undo, Reset, Settings modal, and Close.
- **Per-App Launcher & Saved Alignment**: Pick **Chess.com**, **Lichess**, **Chess · Splend**, **Chess Free**, or any installed app on your phone — starting the overlay automatically launches the chosen chess app and restores its saved 8×8 board position.
- **Adjustable Strength & Style**:
  - **Target Elo**: `400 – 4000 Elo` (step 50)
  - **Target Accuracy**: `50% – 100%` (MultiPV centipawn-loss & softmax candidate blending)
  - **Play Style**: `Default`, `Aggressive`, `Passive`, `Human`
  - **Search Depth**: `1 – 8`
- **Visual Board Coaching**: Anti-aliased emerald best-move arrows (top 1–3 candidates), vertical Evaluation Bar, Move Quality grading (`Best / Good / Inaccuracy / Mistake / Blunder`), Board Warnings (Check / Hanging / Threatened), Pinned Piece markers, and optional Piece Markers.
- **Offline Engine**: Bundles Stockfish 17 (`arm64-v8a`, built in GitHub Actions) with an automatic pure-Kotlin PeSTO alpha-beta/quiescence fallback engine.

## Automated APK Build
Every push to `main` runs `.github/workflows/build-apk.yml`, executes unit tests, compiles `ChessCoachOverlay.apk`, uploads it as an artifact, and publishes it to the `v1.0.0` GitHub Release.
