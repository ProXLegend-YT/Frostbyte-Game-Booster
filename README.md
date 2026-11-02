# FrostByte Game Booster
*Game harder. Stay cooler.*

## Built
1. Foundation, theme, Home, GitHub Actions
2. Game library, per-game profiles, last played, add any game
3. Gaming Mode service + "Game Booster" notification + One-Tap Boost (DND, keep-awake, auto-end)
4. Right-side translucent in-game panel, Touch Lock, Thermal Guard, Memory, Battery, Network
5. Gaming History, Performance tab, Settings
6. Screenshot + Screen Record (MediaProjection, always asks first, notification + STOP)
7. Panel presets (Frost, Dark, AMOLED, Minimal, Performance), choose cards and shortcuts, AMOLED app theme
8. AFK Gaming Mode (keep awake, timer, monitoring, touch lock, auto-stop on low battery / critical heat)
9. Background App Manager (recent apps, guided Force-stop clean-up, measured memory change)

10. Release build on tags (v1.0.0 -> GitHub Release with debug + release APK), Volume and Network shortcuts
11. Auto Clicker (2 clickers, repeat or after-internet-returns, draggable markers, touch shield), Ghost-Touch Test

## Reverted (2026-10)
Removed the "Turbo"/"Coach" performance layer (memory sweep, brightness/sync control, Performance Coach,
throttle detection, auto-sweep) at the user's request — it didn't feel better and added background work.
The app is back to the Stage 1-11 feature set above, plus the Auto Clicker. The touch shield and Touch Lock
pills now need a real 5-second hold (with a filling-ring animation) instead of 1.2s, so a ghost touch can't
unlock them by accident, and the Auto Clicker's black "screen-off" mode was removed — only the clear touch
shield remains.

## Next
On-device fixes from your A7 testing

## Honest by design
No fake FPS, no fake RAM boost, no root, no hidden APIs, no auto-play/macros. Missing data shows "unavailable".
