# Contributing

Thanks for considering a contribution. This project is intentionally small —
one Android service + a Termux client wrapper — so most contributions are
either a focused bug fix, a doc clarification, or new device-support reports.

## Quick start

```bash
git clone <your-fork>
cd temux_llm
bash scripts/install.sh             # builds + installs on a connected phone
```

That same script is what users run. If it works for you, the project works.

## What's helpful

- **Device support reports**: tested on a non-S21+/S25/Fold7 device? Open an issue
  with your device model, SoC, RAM, Android version, and the numbers from
  `litertlm "..."`. We'll add it to the device matrix in the README.
- **Bug fixes** with a clear reproduction path. The `docs/findings-*.md` files
  show the kind of empirical detail we like in bug reports.
- **Doc fixes**: typos, broken links, outdated commands.
- **CI/lint improvements** to `.github/workflows/`.

## What's out of scope

- A UI / chat app — there are excellent ones already
  (e.g., [Google AI Edge Gallery](https://github.com/google-ai-edge/gallery)).
  This project stays CLI-only.
- Network exposure beyond `127.0.0.1`. Every PR that adds a non-loopback bind
  will be rejected.
- A model marketplace / model auto-discovery. Users pick a `.litertlm` and
  push it themselves.
- Termux-native LiteRT-LM (running the runtime inside Termux's app sandbox).
  The Android-app architecture is deliberate.

## Code style

- **Kotlin**: defaults from the Android Gradle Plugin. Run
  `./gradlew :app:lint` before pushing; warnings should not increase.
- **Bash scripts**: `set -euo pipefail`, POSIX-friendly where reasonable.
  `shellcheck` runs in CI.
- **Comments**: explain *why*, not *what*. Especially the load-bearing ones
  (SELinux + filesDir, JNI exec restrictions, NanoHTTPD UTF-8 quirk) — keep them.

## Pull requests

1. Open an issue first for anything beyond a one-line fix.
2. Keep PRs focused. One concern per PR.
3. Update the README's performance / device matrix if your change moves it.
4. Update `CHANGELOG.md` under the **Unreleased** heading.

By submitting a contribution you agree to license it under the project's
Apache 2.0 license (see `LICENSE`).

## Releasing

Tag-driven release (maintainers only):

```bash
git tag -a v0.x.y -m "release v0.x.y"
git push origin v0.x.y
```

The `release.yml` workflow builds an unsigned debug APK and attaches it to a
GitHub Release. Consumers either install that APK directly or rebuild from
source with `bash scripts/install.sh`.
