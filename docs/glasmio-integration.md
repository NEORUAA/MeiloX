# Glasmio integration

MeiloX uses Glasmio `bde07c4b4534565d6dc62243271592f5f9386cfe` through the
sibling source build described in the README. Backdrop recording and effects use
`io.github.neoruaa.glasmio.glass.render`; the old Kyant Backdrop artifact is not a
second rendering backend. Retained third-party capture code keeps its attribution.

## Navigation and scrolling

`GlasmioNavigationBarWithTrailingItem` owns the navigation/search material and
interaction. Search retains its route and action independently of the main tabs.
Its allocation is 62 dp, with a 48 dp compact material.

`MeiloXNavigationScrollState` adapts Glasmio's scroll-away driver to the app's real
list and grid viewports. Each mounted route publishes its viewport through its
existing page header. The root retains one compact history across route changes.
Secondary pages and the player cannot drive that history; the MiniPlayer can
restore its regular layout while the navigation fades in its previous shape.

The driver requires 96 dp of scroll range and 64 dp of consumed movement. Its
independent hint reaches a five-percent shrink. The navigation target uses damping
0.8 and response 0.3 s; hint tracking, deceleration and reset use 0.1, 0.5 and 0.3 s.
Programmatic scrolling does not start a user scroll-away session. MeiloX also
expands after 64 dp of consumed reverse scrolling inside a page, rather than
requiring the list to cross its top boundary. Small direction changes cancel
against that accumulated distance.

## MiniPlayer accessory

The local iOS runtime contains Music resources but lacks the Music executable and
MusicApplication binary. The geometry and motion below come from UIKit 27's phone
floating accessory path. MeloX's Swift `tabViewBottomAccessory` caller supplies the
content reference; its values are not presented as recovered Apple Music constants.

Both outer accessory states are 48 dp high. The regular accessory sits 8 dp above
the navigation. The inline accessory sits between the compact navigation and
auxiliary materials, with 14 dp gaps to their visible edges. MeiloX retains its
existing artwork, text, buttons and content layout; the migration changes the
outer accessory shell and its motion. The Swift MeloX caller remains a reference
for the system accessory API, rather than replacing MeiloX's content styling.

The accessory has separate horizontal and vertical presentation channels:

| Transition | Intermediate frame | Intermediate spring | Final spring |
| --- | --- | --- | --- |
| Collapse | Target X/width, current Y/height | 0.72 / 0.36 s | 0.76 / 0.42 s |
| Expand | Current X/width, target Y/height | 0.72 / 0.30 s | 0.72 / 0.36 s |

The final presentation starts after 50 ms, overlapping the intermediate spring.
The final model frame is written synchronously. A model-frame midpoint movement
of at most 15 dp uses one critical 0.3 s spring instead. Compose preserves channel
continuity when retargeting; its integrator is not UIKit's private animator.

The navigation and accessory use Glasmio's `glasmioNavigationScrollHint` modifier
with a shared parent pivot. MeiloX does not apply an additional five-percent scale.
The pivot uses
the regular layout reservation through the bottom of the window, including bottom
insets, even when the visible accessory is inline. MeiloX applies the equivalent
transform to the two visible layers rather than scaling the full-screen player.

During navigation resizing, Glasmio supplies the current material source size and
visual bounds from the same geometry. A retained layout registration therefore
cannot stretch the capsule's circular end caps into elliptical corners.

## Material and player lifetime

The MiniPlayer keeps its own `GlassSurface`. Glasmio's navigation contact owns two
active members; adding the MiniPlayer as a third member would invalidate that
surface. All bottom controls sample the recorded page, excluding themselves.

Artwork, press and content coordinates remain part of the player sheet's opening
capture. While the full player is visible, the hidden MiniPlayer settles to its
return endpoint. Playback restoration still reverses an in-flight dismissal.
Route-specific MiniPlayer appearance remains independent of the root tab theme.

The Activity selects supported HDR output before creating its Compose layers.
Unsupported displays retain SDR output; this does not add a theme-level brightness
boost or alter the user's transparency preferences.

Application switches use `MeiloXSwitch` to retain the current theme accent, with
explicit tint overrides still supported. Settings' custom grouped rows use
`SettingsGroupedList` and `SettingsCard`, which apply Glasmio's edge-aware separator
modifier instead of treating a standalone card as a grouped row.

Local native decompilation, parameters and caller references are retained in
[the ignored research folder](../.local-research/ios/applemusic-mini-player/FINDINGS.md).
