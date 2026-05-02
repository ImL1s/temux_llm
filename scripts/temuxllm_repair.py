#!/usr/bin/env python3
# temuxllm_repair.py — single source of truth for tool-call JSON repair
# and tool-result prose rendering used by both
# scripts/litertlm-native-wrapper.sh and scripts/install-termux-native.sh
# (the v0.5.x heredoc-embedded copy is removed in v0.7.0).
#
# Mirrors the Kotlin implementation in
# android/app/src/main/kotlin/dev/temuxllm/ChatFormat.kt:
#   * `repair_tool_call_json(tail)` ↔ ChatFormat.repairToolCallJson
#   * `render_tool_result_prose(name, content)` ↔ unified G2 prose
#
# Usage:
#   echo '<raw model output>' | python3 temuxllm_repair.py repair
#   python3 temuxllm_repair.py prose <tool_name> <result_text>
#   python3 temuxllm_repair.py parse_tool_calls < raw_model_output
#
# The wrapper scripts call this via heredoc-less subprocess so neither
# embeds Python source -- single source of truth, no drift.

from __future__ import annotations
import json
import sys
from typing import List, Optional, Tuple

# --- Kotlin parity caps (security review HIGH#1 + code review HIGH#1) -------
MAX_REPAIR_LEN = 64 * 1024
MAX_REPAIR_DEPTH = 128


def repair_tool_call_json(tail: str) -> Optional[str]:
    """Stack-based balanced-bracket rewriter, mirrors ChatFormat.kt:312.

    Returns the repaired JSON string, or None when the input either
    doesn't start with `{`, exceeds the size cap, exceeds the nesting
    cap, or contains an unterminated string we can't recover from.
    """
    if not tail or tail[0] != "{":
        return None
    if len(tail) > MAX_REPAIR_LEN:
        return None
    out = []
    stack: List[str] = []
    in_string = False
    escaped = False
    for c in tail:
        if in_string:
            out.append(c)
            if escaped:
                escaped = False
            elif c == "\\":
                escaped = True
            elif c == '"':
                in_string = False
        else:
            if c == '"':
                out.append(c)
                in_string = True
            elif c == "{":
                if len(stack) >= MAX_REPAIR_DEPTH:
                    return None
                out.append(c)
                stack.append("{")
            elif c == "[":
                if len(stack) >= MAX_REPAIR_DEPTH:
                    return None
                out.append(c)
                stack.append("[")
            elif c == "}":
                if stack and stack[-1] == "{":
                    stack.pop()
                    out.append(c)
                # stray `}`: drop
            elif c == "]":
                while stack and stack[-1] == "{":
                    out.append("}")
                    stack.pop()
                if stack and stack[-1] == "[":
                    stack.pop()
                    out.append(c)
                # stray `]`: drop
            else:
                out.append(c)
    if in_string:
        return None
    while stack:
        ch = stack.pop()
        out.append("}" if ch == "{" else "]")
    return "".join(out)


def find_tool_calls_object_start(s: str) -> int:
    """Mirrors ChatFormat.findToolCallsObjectStart."""
    key = '"tool_calls"'
    i = s.find(key)
    if i < 0:
        return -1
    j = i - 1
    while j >= 0:
        if s[j] == "{":
            return j
        j -= 1
    return -1


def find_matching_brace(s: str, start: int) -> int:
    """Mirrors ChatFormat.findMatchingBrace."""
    if start < 0 or start >= len(s) or s[start] != "{":
        return -1
    depth = 0
    in_string = False
    escaped = False
    for i in range(start, len(s)):
        c = s[i]
        if in_string:
            if escaped:
                escaped = False
            elif c == "\\":
                escaped = True
            elif c == '"':
                in_string = False
        else:
            if c == '"':
                in_string = True
            elif c == "{":
                depth += 1
            elif c == "}":
                depth -= 1
                if depth == 0:
                    return i
    return -1


def parse_tool_calls(raw: str, allowed_names: Optional[List[str]] = None) -> Tuple[str, List[dict]]:
    """Mirrors ChatFormat.parseToolCalls. Returns (text, tool_calls)."""
    if not raw or not raw.strip():
        return raw, []
    idx = find_tool_calls_object_start(raw)
    if idx < 0:
        return raw.strip(), []
    end = find_matching_brace(raw, idx)
    parsed = None
    consumed_end = end
    if end >= 0:
        try:
            parsed = json.loads(raw[idx : end + 1])
        except json.JSONDecodeError:
            parsed = None
    if parsed is None:
        repaired = repair_tool_call_json(raw[idx:])
        if repaired:
            try:
                parsed = json.loads(repaired)
                consumed_end = len(raw) - 1
            except json.JSONDecodeError:
                parsed = None
    if not isinstance(parsed, dict):
        return raw.strip(), []
    arr = parsed.get("tool_calls")
    if not isinstance(arr, list):
        return raw.strip(), []
    calls: List[dict] = []
    for o in arr:
        if not isinstance(o, dict):
            continue
        name = o.get("name") or (o.get("function", {}).get("name") if isinstance(o.get("function"), dict) else "")
        if not isinstance(name, str) or not name.strip():
            continue
        if allowed_names and name not in allowed_names:
            continue
        args = o.get("arguments")
        if isinstance(args, str):
            try:
                args = json.loads(args)
            except json.JSONDecodeError:
                args = {}
        if not isinstance(args, dict):
            fn = o.get("function") if isinstance(o.get("function"), dict) else None
            if fn:
                fa = fn.get("arguments")
                if isinstance(fa, str):
                    try:
                        args = json.loads(fa)
                    except json.JSONDecodeError:
                        args = {}
                elif isinstance(fa, dict):
                    args = fa
        if not isinstance(args, dict):
            args = {}
        calls.append({"name": name, "arguments": args})
    text_left = raw[:idx]
    text_right = raw[consumed_end + 1 :] if consumed_end + 1 <= len(raw) else ""
    return (text_left + text_right).strip(), calls


def render_tool_result_prose(name: str, content: str) -> str:
    """Unified tool-result prose, mirrors v0.6.0 G2 ChatFormat output."""
    nm = name if name and name.strip() else "tool"
    return f"Tool[{nm}]: {content}"


# --- CLI driver -----------------------------------------------------------


def _cmd_repair(argv: List[str]) -> int:
    raw = sys.stdin.read()
    out = repair_tool_call_json(raw)
    if out is None:
        sys.stderr.write("repair_tool_call_json: input not repairable\n")
        return 2
    sys.stdout.write(out)
    return 0


def _cmd_prose(argv: List[str]) -> int:
    if len(argv) < 2:
        sys.stderr.write("usage: prose <tool_name> <result_text>\n")
        return 2
    sys.stdout.write(render_tool_result_prose(argv[0], argv[1]))
    return 0


def _cmd_parse_tool_calls(argv: List[str]) -> int:
    raw = sys.stdin.read()
    text, calls = parse_tool_calls(raw, argv if argv else None)
    json.dump({"text": text, "tool_calls": calls}, sys.stdout)
    return 0


def main(argv: List[str]) -> int:
    if len(argv) < 2:
        sys.stderr.write("usage: temuxllm_repair.py {repair|prose|parse_tool_calls} [args...]\n")
        return 2
    cmd, rest = argv[1], argv[2:]
    if cmd == "repair":
        return _cmd_repair(rest)
    if cmd == "prose":
        return _cmd_prose(rest)
    if cmd == "parse_tool_calls":
        return _cmd_parse_tool_calls(rest)
    sys.stderr.write(f"unknown subcommand: {cmd}\n")
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv))
