# Contributing

Thanks for helping improve NuvioTV.

## Strict rules — read before opening anything

These rules are enforced strictly. Issues and PRs that do not follow them will be closed without review.

---

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

Translation PRs are allowed, as long as they stay focused on translation/localization work and do not bundle unrelated feature or UI changes.

### Large PRs and large changes

**Any large PR or change that is not a simple bug fix must be discussed and approved via a feature request issue first.**

1. Open a **Feature Request** issue describing the change.
2. Wait for explicit maintainer approval on that issue.
3. Link the approved issue in your PR description.

PRs that introduce large changes without a linked, approved feature request **will not be reviewed at all** and will be closed immediately. No exceptions.

This applies to — but is not limited to — UI changes, new features, architecture changes, dependency additions, and large refactors.

---

## Where to ask questions

- Use **Issues** for bugs, feature requests, setup help, and general support.

---

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

---

## Feature requests (rules)

Please include:

- The problem you are solving (use case)
- Your proposed solution
- Alternatives considered (if any)

Opening a feature request does **not** mean a pull request will be accepted for it. If the feature affects product scope, UX direction, or adds a significant new surface area, do not start implementation unless a maintainer explicitly approves it first.

**Large changes require an approved feature request before any PR is submitted.** See the [Large PRs and large changes](#large-prs-and-large-changes) section above.

---

## Before opening a PR

Please make sure your PR is all of the following:

- Small in scope
- Focused on one problem
- Clearly aligned with the current direction of the project
- Not cosmetic-only, unless it is a translation PR
- Not a new major feature unless it was discussed and approved first
- **If large or non-trivial: linked to an approved feature request issue**

PRs that do not fit this policy will be closed without merge so review time can stay focused on bugs, regressions, and small improvements.

---

## One issue per problem

Please open separate issues for separate bugs/features. It makes tracking, fixing, and closing issues much faster.
