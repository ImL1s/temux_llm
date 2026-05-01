# Security policy

## Supported versions

This is a small hobby/research project. Only the `main` branch and the latest
GitHub Release receive security fixes. Older tags are frozen.

## Threat model

This project ships:

- An Android foreground service that binds an HTTP server to `127.0.0.1:11434`.
- A bundled inference runtime (LiteRT-LM via Maven) that loads a `.litertlm`
  model from device-local storage.
- A Termux-side shell wrapper (`litertlm`).

Out of scope by design:

- Network exposure beyond loopback. Any PR that binds to `0.0.0.0` or any
  non-loopback interface is treated as a critical bug and will not be merged.
- Privilege escalation (no root, no `su` calls).
- Untrusted model files. The runtime trusts the bytes you push to
  `/data/local/tmp/litertlm/model.litertlm`. Do not push models from
  unverified sources.

## Reporting a vulnerability

Email **aa22306546@hotmail.com** with:

- A description of the issue.
- A minimal reproduction (commands run + observed vs. expected).
- Affected versions.

Please do **not** open a public GitHub issue for sensitive reports.

You should expect a first response within 7 days. We don't have a paid bug
bounty.

## What counts

We treat the following as security issues:

- Anything that exposes the localhost API to a non-loopback caller.
- Anything that bypasses the SELinux scopes the runtime relies on (writing
  outside the app's `filesDir`, executing files from the public `/data/local/tmp`).
- Command-injection vectors in the shell wrapper or `install.sh`.
- Crashes that allow another app to read or write the model file or service
  state.

Bugs in the upstream LiteRT-LM runtime, the bundled NanoHTTPD, or in any
model's behavior should be reported to those projects directly.
