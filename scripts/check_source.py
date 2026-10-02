#!/usr/bin/env python3
"""Check only project source for accidental runtime/private material before publishing."""
from pathlib import Path
import re
import sys

root = Path(__file__).resolve().parents[1]
skip_dirs = {".git", ".gradle", "build", "node_modules", ".data", ".runtime", ".idea"}
patterns = {
    "private-key": re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"),
    "github-token": re.compile(r"\b(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{50,})\b"),
    "openai-key": re.compile(r"\bsk-(?:proj-)?[A-Za-z0-9_-]{35,}\b"),
    "aws-access-key": re.compile(r"\b(?:AKIA|ASIA)[A-Z0-9]{16}\b"),
    "local-user-path": re.compile(r"/(?:Users|home)/[A-Za-z0-9._-]+/"),
    "private-conversation-context": re.compile(r"\b(?:Sentinel_[A-Za-z0-9]+|source_" r"thread_id)\b"),
}
findings = []
for path in sorted(root.rglob("*")):
    relative = path.relative_to(root)
    if not path.is_file() or any(part in skip_dirs for part in relative.parts):
        continue
    if path.suffix in {".jar", ".png", ".jpg", ".apk", ".zip"}:
        continue
    if path.name.endswith(".private.json") or path.name in {".env", "local.properties"} or path.suffix in {".jks", ".keystore"}:
        findings.append(f"{relative}: forbidden runtime/credential file")
        continue
    source = path.read_text(encoding="utf-8", errors="replace")
    for name, pattern in patterns.items():
        for match in pattern.finditer(source):
            # Report file/line/category, never the matched value.
            line = source.count("\n", 0, match.start()) + 1
            findings.append(f"{relative}:{line}: {name}")
if findings:
    print("\n".join(findings), file=sys.stderr)
    sys.exit(1)
print("Project source check passed (patterns only; independent review still required).")
