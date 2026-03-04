# Contributing

Thanks for helping improve NuvioTV.

## Where to ask questions

- Please use **GitHub Discussions** for questions, setup help, and general support.
- Use **Issues** for actionable bugs and feature requests.

## Bug reports (rules)

To keep issues fixable, bug reports should include:

- App version (release version or commit hash)
- Platform + device model + Android version
- Install method (release APK / CI / built from source)
- Steps to reproduce (exact steps)
- Expected vs actual behavior
- Frequency (always/sometimes/once)

Logcat is **optional**, but it helps a lot for playback/crash issues.

### How to capture logs (optional)

If you can, reproduce the issue once, then attach a short log snippet from around the time it happened:

```sh
adb logcat -d | tail -n 300
```

If the issue is a crash, also include any stack trace shown by Android Studio or `adb logcat`.

## Feature requests (rules)

Please include:

- The problem you are solving (use case)
- Your proposed solution
- Alternatives considered (if any)

## One issue per problem

Please open separate issues for separate bugs/features. It makes tracking, fixing, and closing issues much faster.
