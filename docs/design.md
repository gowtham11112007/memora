# Design language

**Feel:** bold and friendly. Strong ink panels and lime highlights over soft tinted tiles. Material 3, follows system light/dark.

The Claude Design canvas (https://claude.ai/artifact/RrKGW7mmcydKFnwmd3fR71) shows the earlier lavender version. The app has moved on, so the code and the renders below are now the reference.

## Tokens (ui/theme)
- Accent: violet `#6A5AF0` (dark mode `#B2ABFA`). Highlight: lime `#D6F24C`, always with ink text.
- Tiles: lavender `#EAE6FC`, periwinkle `#CBC6FB`, mint `#CFEADB`, pink `#FFD3E2`.
- Neutrals: paper `#F4F3F9`, white cards, ink `#15141C`, muted `#62617A`.
- Type: Outfit, bundled in `res/font` (SIL Open Font License).

## Taken from each reference
- **Yellow/black:** coloured sheet behind the header, folder-tab card, ink panel with one highlighted row, notched bottom bar with a round centre button.
- **Lime/black:** vivid greeting block, ink answer cards, tiles with a round arrow, pill input.
- **Crypto wallet:** big amount first, paired action pills, bars with a ghost behind them, ink panels.
- **Lavender:** soft tinted tiles, hatched fills, rounded type.

## Signature components (ui/components/Signature.kt)
`FolderTabShape`, `NotchedBarShape`, `ArrowChip`, `RingStat`.

## Motion
Pages slide and fade in the direction of travel. The period thumb and folder tab slide. Nav items spring and the Ask spark turns. Content rises in, amounts count up, bars and rings grow, and typing dots pulse.

## Checking the design
`./gradlew testDebugUnitTest -Pshots` renders every screen with sample data to `app/build/shots/`.
These are JVM renders, so check the final look and motion on a phone.
