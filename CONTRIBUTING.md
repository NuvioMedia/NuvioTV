# Contributing

Thanks for helping improve NuvioTV.

## PR policy

Pull requests are currently intended for:

- Reproducible bug fixes
- Small stability improvements
- Minor maintenance work
- Small documentation fixes that improve accuracy
- Translation updates

Pull requests are generally **not** accepted for:

- New major features
- Product direction changes
- Large UX / UI redesigns
- Cosmetic-only changes
- Refactors without a clear user-facing or maintenance benefit

For feature ideas and bigger changes, please open an issue first. Feature implementation is usually kept in-house unless it has been discussed and explicitly approved beforehand.

Translation PRs are allowed, as long as they stay focused on translation/localization work and do not bundle unrelated feature or UI changes.

## Where to ask questions

- Use **Issues** for bugs, feature requests, setup help, and general support.

## Bug reports (rules)

To keep issues fixable, bug reports should include:

- A short, specific issue title that describes the bug
- App version (release version or commit hash)
- Platform + device model + Android version
- Install method (release APK / CI / built from source)
- Steps to reproduce (exact steps)
- Expected vs actual behavior
- Frequency (always/sometimes/once)

Do not leave the title as just `[Bug]:` or another generic placeholder.

Logcat is optional for most issues, but it is **required** for crash / force-close reports.

### How to capture logs (optional)

If you can, reproduce the issue once, then attach a short log snippet from around the time it happened:

```sh
adb logcat -d | tail -n 300
```

If the issue is a crash, include a stack trace or log snippet from Android Studio or `adb logcat`.

## Feature requests (rules)

Please include:

- The problem you are solving (use case)
- Your proposed solution
- Alternatives considered (if any)

Opening a feature request does **not** mean a pull request will be accepted for it. If the feature affects product scope, UX direction, or adds a significant new surface area, do not start implementation unless a maintainer explicitly approves it first.

## Before opening a PR

Please make sure your PR is all of the following:

- Small in scope
- Focused on one problem
- Clearly aligned with the current direction of the project
- Not cosmetic-only, unless it is a translation PR
- Not a new major feature unless it was discussed and approved first

PRs that do not fit this policy will usually be closed without merge so review time can stay focused on bugs, regressions, and small improvements.

## One issue per problem

Please open separate issues for separate bugs/features. It makes tracking, fixing, and closing issues much faster.
