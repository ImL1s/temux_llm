#!/usr/bin/env bash
# scripts/_lib.sh — shared validation helpers. Source this; do not run directly.
# Centralises env-var validation (DEVICE_FOLDER / BACKEND / single-name) so
# command-injection and path-traversal surfaces in adb shell strings close in one place.

# DEVICE_FOLDER: must live under /data/local/tmp/ and contain only [A-Za-z0-9_./-].
omc_validate_device_folder() {
  local d="${1:?missing DEVICE_FOLDER}"
  case "$d" in
    /data/local/tmp/*) ;;
    *) echo "FATAL: DEVICE_FOLDER must start with /data/local/tmp/ (got: $d)" >&2; return 1 ;;
  esac
  case "$d" in
    *[^A-Za-z0-9/_.-]*)
      echo "FATAL: DEVICE_FOLDER contains unsafe characters (got: $d)" >&2; return 1 ;;
  esac
  return 0
}

# BACKEND: only cpu|gpu accepted (matches what v0.9.0 binary supports per --helpfull).
omc_validate_backend() {
  local b="${1:?missing BACKEND}"
  case "$b" in
    cpu|gpu) ;;
    *) echo "FATAL: BACKEND must be cpu or gpu (got: $b)" >&2; return 1 ;;
  esac
  return 0
}

# Bare filename: a single non-empty component containing only [A-Za-z0-9_.-]. Refuses
# "..", "/", or shell metacharacters. Used by cleanup_device_tmp.sh to sanitize
# entries that came from device-side `ls` (untrusted source).
omc_validate_simple_name() {
  local n="${1-}"
  [[ -n "$n" && "$n" != "." && "$n" != ".." ]] || return 1
  case "$n" in
    *[^A-Za-z0-9_.-]*) return 1 ;;
    *) return 0 ;;
  esac
}
