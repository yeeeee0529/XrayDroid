# XrayDroid UI design rules

Binding rules for any agent that adds or changes native UI in this repository. The UI follows
Material 3 Expressive (M3E) and the Android predictive back design guidance. When a rule here
conflicts with a general habit, follow this file. When you need something this file does not
cover, extend the shared components and update this file in the same change.

Sources: <https://m3.material.io> and
<https://developer.android.com/design/ui/mobile/guides/patterns/predictive-back>.

## 1. Where things live

| Path | Purpose |
| --- | --- |
| `app/src/main/java/io/github/xraydroid/ui/theme/XrayDroidTheme.kt` | `MaterialExpressiveTheme`; dynamic color on Android 12+, fixed fallback schemes below. |
| `ui/components/AppPage.kt` | Page scaffold used by every screen. |
| `ui/components/SegmentedList.kt` | `SegmentedColumn`, `SegmentedItem`, `SegmentRow`, `segmentShape`, `IconBadge`, `SectionHeader`. |
| `ui/components/StatusHero.kt` | Service status hero shared by the dashboard and frp instances. |
| `ui/components/Buttons.kt` | `ButtonLabel` (leading icon + text inside a button). |
| `ui/PredictiveBackNavigation.kt` | The only navigation host; owns back and forward transitions. |
| `app/src/main/res/drawable/ic_*.xml` | Icons. |

Reuse these before writing new layout code. Do not create a second scaffold, list style, status
card, or navigation container.

## 2. Page structure

- Every screen is one `AppPage`. It provides a `LargeFlexibleTopAppBar` with
  `exitUntilCollapsedScrollBehavior`, and a single centered `LazyColumn` capped at 760 dp wide.
- A page has exactly one scroll container: the `AppPage` list. Never nest another vertical
  scrollable or add a second `LazyColumn`. Instrumented tests locate the list with
  `hasScrollToNodeAction()` and require it to be unique.
- List spacing is fixed by `AppPage`: 12 dp between items, 16 dp horizontal padding, 8 dp top,
  24 dp bottom. Do not override it per screen. To group rows tightly, put them in one `item`
  inside a `SegmentedColumn`.
- Page background is `surfaceContainer`. Content surfaces sit on it in `surfaceBright`.
- The page title goes in the top app bar only. Do not repeat it as a heading in the list.
- Top-bar actions are `IconButton`s. Back is supplied through `onBack` and `backLabel`.
- Floating controls (FAB, floating toolbar, feedback pill) go in `floatingActionButton` or
  `bottomOverlay`, and the page must pass `bottomClearance = FloatingControlClearance` so the
  last list item can scroll clear of them.

## 3. Color, shape, type, motion

- Take every color from `MaterialTheme.colorScheme`. No hex literals in screens. The only
  literals are the pre-Android-12 fallback schemes in `XrayDroidTheme.kt`.
- Surface roles:
  - `surfaceContainer`: page background, nested sections inside a card.
  - `surfaceBright`: list rows and cards.
  - `surfaceContainerHigh` / `surfaceContainerHighest`: inset blocks such as a URL, a log block,
    or a neutral status block.
  - `primaryContainer`: active or connected state. `secondaryContainer`: idle state and the
    selected row. `errorContainer`: failure or unavailable state.
  - `inverseSurface`: transient feedback pill above the floating toolbar.
- Take shapes from `MaterialTheme.shapes` (`large` for section cards and blocks, `extraLarge`
  for feature cards, `extraLargeIncreased` for the status hero). Do not write
  `RoundedCornerShape(n.dp)` in screens. The only custom radii are the segmented-list tokens in
  `SegmentedList.kt` (20 dp outer, 4 dp between neighbours, 2 dp gap).
- Decorative badge shapes come from `MaterialShapes.*.toShape()`.
- Use the emphasized type styles for hierarchy: `headlineMediumEmphasized` for a hero title,
  `titleLargeEmphasized` for a card title, `titleMediumEmphasized` in `primary` for section
  headers (use `SectionHeader`). Row titles are `titleMedium`; supporting text is `bodyMedium`
  or `bodySmall` in `onSurfaceVariant`. Technical values (URLs, addresses, versions, logs) use
  `FontFamily.Monospace`.
- State changes animate: container colors with `animateColorAsState`, chevrons with a 180°
  rotation. Indeterminate progress uses `ContainedLoadingIndicator` or `LoadingIndicator`, not
  a linear bar.

## 4. Components

- **Lists of settings, entries, or options**: `SegmentedColumn` + `SegmentedItem`. Pass the
  item's `index` and the group `count` so corners are computed correctly. Rows are at least
  64 dp tall. Use `leading` for an `IconBadge` or a selection control and `trailing` for a
  chevron, value, `Switch`, or `IconButton`.
- **Single-choice rows**: a segmented row with `Modifier.selectable(role = Role.RadioButton)`
  inside a `selectableGroup`, a `RadioButton(onClick = null)` as `leading`, and
  `secondaryContainer` when selected. See `NetworkCard.kt`.
- **Two-way mode switch**: two `ToggleButton`s in a `Row` spaced by
  `ButtonGroupDefaults.ConnectedSpaceBetween`, using `connectedLeadingButtonShapes()` and
  `connectedTrailingButtonShapes()`, each with `Role.RadioButton`.
- **Service status**: `StatusHero`. Map state to `StatusTone` (`Active`, `Idle`, `Error`), pass
  `busy` while a transition is running, and put details and action buttons in its content.
- **Buttons**: use `shapes = ButtonDefaults.shapes()` so the shape morphs on press, and
  `ButtonLabel(icon, text)` for content. Primary actions are at least 56 dp tall; a lone start
  action is 64 dp with `large = true`. One filled button per group; the secondary action is
  outlined, tonal, or text.
- **Page-level actions that must stay reachable while scrolling** (save, validate):
  `HorizontalFloatingToolbar` with `vibrantFloatingToolbarColors()` in `bottomOverlay`. Show
  the result of those actions in the feedback pill directly above the toolbar, not in the list.
- **Primary creation action on a list page**: `ExtendedFloatingActionButton`, using the
  content-lambda overload (see section 6).
- **Collapsible sections**: a `Surface` card with a clickable header row and an
  `ic_expand_more` chevron. Never use text glyphs such as `▸` or `▾` to show state.
- **Dialogs** stay `AlertDialog` with text buttons.

## 5. Icons and text

- Icons are vector drawables in `res/drawable/ic_<name>.xml`: 24 dp, 24×24 viewport, single
  path with `android:fillColor="#FF000000"`, tinted at the call site. Add
  `android:autoMirrored="true"` to directional icons. Load them with `painterResource`.
- Do not add an icon dependency (`material-icons-extended` or similar). Add only icons you use;
  lint treats unused resources as errors.
- All user-facing text lives in the three `strings.xml` files, kept in the same order
  (`scripts/check-string-resources.py` enforces this). Reuse an existing string before adding
  one. Write no user-facing literals in Kotlin.

## 6. Accessibility

- Every icon-only control needs a `contentDescription` from a string resource. Icons next to a
  visible label use `contentDescription = null`.
- Expose expanded state with the `expand` / `collapse` semantics actions on the header, not
  with text.
- Status text that changes on its own uses `liveRegion = LiveRegionMode.Polite`.
- Do not use the `ExtendedFloatingActionButton(icon = …, text = …)` overload: in the pinned
  Material 3 version its label is missing from the merged semantics tree. Use the content
  overload with `ButtonLabel`.
- Do not expose a node to accessibility while its layer is transparent. Accessibility clients
  cache it as invisible and alpha changes send no update. `PredictiveBackNavigation` handles
  this for page transitions; follow the same approach for any new fade-in.

## 7. Navigation and predictive back

- All pages are rendered by `PredictiveBackNavigation`. The hierarchy is defined by `parentOf`
  in `MainActivity`: home → settings → network / frp → frp instance. Add a page by extending
  `parentOf` and the `when` in `MainActivity`; do not add another back handler for plain
  navigation.
- Back transition (full-screen surface spec): the exiting page scales 100% → 90% and is fully
  faded out at 35% progress; the entering page scales 110% → 100% and starts fading in at 35%.
  Scale uses `CubicBezierEasing(0.1, 0.1, 0, 1)`. Commit takes 250 ms, cancel 200 ms. A
  cancelled gesture must restore the page with its state intact.
- Forward transition into a child page is the mirror: parent 100% → 110% fading out, child
  90% → 100% fading in, 300 ms. Non-adjacent jumps (for example from a notification) are
  instant.
- These constants live at the top of `PredictiveBackNavigation.kt`. Change them there only, and
  keep them consistent with the Android guidance.
- A page with unsaved changes intercepts back with `BackHandler(enabled = dirty)` and shows a
  confirmation dialog; when clean it must leave back to the predictive transition.
- A second back during a commit animation must navigate one more level. Do not reintroduce a
  reset that discards an in-flight gesture.
- The app is edge-to-edge. Do not place drag targets or horizontal swipe gestures inside the
  system gesture insets at the left and right screen edges.
- Pages under `frp` set `FLAG_SECURE`. Keep that behaviour when changing navigation.

## 8. Verification for UI changes

- Run `./gradlew :app:ktlintFormat`, then
  `:app:ktlintCheck :app:lintDebug :app:testDebugUnitTest :app:assembleDebug`, with JDK 17.
- Run `:app:connectedDebugAndroidTest -PvalidationApplicationId=io.github.xraydroid.validation`
  for any change to navigation, page structure, or control labels. The tests find nodes by
  Traditional Chinese text, so the device or emulator system locale must be `zh-TW`; restore it
  afterwards if you changed it.
- Navigation tests are timing sensitive. After changing `PredictiveBackNavigation.kt`, run
  `SettingsNavigationTest` at least ten times in a row; one pass is not evidence.
- When you change a label, an icon-only control, or where a control sits, update the affected
  instrumented tests in the same change. List content can scroll under floating controls, so
  tests click in-list nodes with `SemanticsActions.OnClick` rather than touch input.
- Look at the result. `screencap` works for home, settings, and network. The frp pages are
  blank under `FLAG_SECURE`; render them in a temporary instrumented test with
  `captureToImage()` and do not commit that test.
- Record what was and was not verified in `docs/PROGRESS.md`.
