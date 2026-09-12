# API compatibility fixes after the initial lint triage

This phase starts from 562 UnsafeOptInUsageError and 59 RestrictedApi findings.
The fresh fullDebug lint run now reports **zero non-translation errors**:
UnsafeOptInUsageError, RestrictedApi and RememberInComposition all reach zero.
The 2,303 remaining errors are exclusively MissingTranslation; 1,763 warnings
and 25 hints also remain. No translations were removed to obtain this result.
Evidence: `output/verification/20260912-140606-734/lint-inventory.json`.

The JVM suite contains 1,293 cases: **1,290 passed, three existing skips, zero
failures or errors**. The added provider mapping tests and Compose recomposition
tests pass. Evidence: `output/verification/20260912-140322-260/results.json`.

## Intentional Media3 integration

Opt-ins use `androidx.annotation.OptIn`, scoped to the affected functions,
properties, and Media3 integration classes. Kotlin's `kotlin.OptIn` does not
satisfy the Java/AndroidX marker contract. No project-wide opt-in or lint
suppression was added. The affected classes implement player, decoder, subtitle
or extractor integration; one-off uses in large storage/viewmodel/utility
classes are annotated on the individual member.

## TV provider public API

Only `androidx.tvprovider:tvprovider` is updated, from 1.0.0 to stable 1.1.0.
The [official release notes](https://developer.android.com/jetpack/androidx/releases/tvprovider)
document public access to PreviewProgramColumns and a PreviewChannelHelper
crash fix. Both 1.0.0 and 1.1.0 source JARs were inspected from Google Maven.
The base program builders remain restricted even in 1.1.0, so merely upgrading
does not resolve their use.

ProgramBuilder and AndroidTvChannelManager now construct ContentValues using
the public provider schema. They preserve identity, intent, title, channel,
weight, artwork shape, duration, playback position, last engagement, season and
episode display numbers. Unknown preview artwork and progress fields still
explicitly clear old values on update. Watch Next still uses synthetic progress
when only a percentage is known. Season/episode numbers remain strings as the
old builder writes on the app's supported API 24+ devices.

Four JVM tests capture the actual ContentValues writes with framework methods
mocked. Two instrumentation tests passed on an Android TV API 36 emulator under the
target app UID, exercising synthetic preview and Watch Next insert/read/update/
delete. Cleanup targets only the uniquely identified rows created by each test.

## Back and detail restoration

The restricted inherited Activity.dispatchKeyEvent override is removed. The
outer Compose preview-key handler consumes remote Back while the auto-next
overlay is present and dismisses it on key-up. This interception precedes
destination handlers and does not depend on their registration order. It also
consumes repeats and release after an already-handled long press. A lifecycle-owned dispatcher callback is registered while the overlay is visible.
When the destination entry reaches RESUMED, the callback is removed and re-added
after the activated destination callbacks. Disposal removes both callback and
lifecycle observer. The emulator test exposed that an entry-identity key alone
registered too early, before NavHost attached the later destination.

Detail restoration uses the public NavController.getBackStackEntry(route).
The retrieved topmost detail is the same target as popBackStack(detailRoute).
For [Detail A, Detail B, Player A], the old scan could write focus into A but
then pop to B. The new content check navigates to A instead of restoring B with
the wrong focus. Navigation checks must cover normal player-to-detail return,
missing detail fallback, and the two-detail case.

## Minimum SDK and resource bytes

- API 24–28 collection export uses the system CreateDocument picker instead of
  referencing MediaStore.Downloads (API 29). API 29+ keeps the Downloads path.
  A missing document provider reports export failure instead of crashing.
- API 24–25 process cleanup uses Process.destroy; API 26+ keeps destroyForcibly.
  No torrent feature was removed or given extra priority.
- Four local web servers load a PNG from R.raw.web_app_logo_wordmark. Its bytes
  are copied exactly from the existing drawable PNG; image/png responses retain
  their format. Drawable resources remain available for the Android UI.

## Final Windows build evidence

The final application APK assembled successfully in 293.2 seconds with the
verification script's 12 GiB heap and two workers, without an out-of-memory
failure. Evidence: `output/verification/20260912-135752-268/results.json`.
The final JVM run took 115.8 seconds; lint completed in 177.6 seconds and
failed only on the retained missing translations. These were separate Gradle
invocations rather than one combined build/test/lint process.

Final x86_64 APK SHA-256:
`783857EE594BA2732B428E606D6380F80F19F3E5B31B77A7476A88375CD275BA`.
The instrumentation APK uses the same locally configured audit signing
certificate as the target. It was assembled separately with the same Gradle
memory limits and runs only the newly added targeted classes; pre-existing
native benchmark instrumentation was not executed.
The unchanged stable overlay instrumentation test failed with entry-key-only
registration and passed after lifecycle re-registration. The final three device
tests passed in 3.569 seconds (`output/lint-device/instrumentation-final.txt`):
Watch Next CRUD, preview-channel/program CRUD, and overlay Back priority across
real Settings navigation. The overlay test also verifies remote Back, dispatcher
Back, and the following Back restoring destination focus. It proves the settled
destination case; dispatcher priority during an in-flight navigation animation
was not separately asserted. Remote Back is handled by the root key preview
throughout the transition.