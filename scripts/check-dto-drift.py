#!/usr/bin/env python3
import re
import sys
from pathlib import Path
import yaml

REPO_ROOT = Path(__file__).resolve().parent.parent
CONFIG_PATH = REPO_ROOT / "scripts" / "dto-pairs.yaml"


def extract_kotlin_classes(text: str) -> dict[str, list[str]]:
    classes: dict[str, list[str]] = {}
    header_pattern = re.compile(
        r"\bdata\s+class\s+(\w+)(?:<[^>]+>)?\s*\(",
    )
    for match in header_pattern.finditer(text):
        class_name = match.group(1)
        start = match.end()
        depth = 1
        i = start
        while i < len(text) and depth > 0:
            ch = text[i]
            if ch == "(":
                depth += 1
            elif ch == ")":
                depth -= 1
            i += 1
        body = text[start : i - 1]
        fields: list[str] = []
        param_pattern = re.compile(
            r'(?:@JsonProperty\(\s*"([^"]+)"\s*\)\s*)?(?:val|var)\s+(\w+)'
        )
        for json_name, kotlin_name in param_pattern.findall(body):
            fields.append(json_name or kotlin_name)
        classes[class_name] = fields
    return classes


def extract_ts_interfaces(text: str) -> dict[str, dict]:
    interfaces: dict[str, dict] = {}
    iface_pattern = re.compile(
        r"\bexport\s+interface\s+(\w+)(?:<[^>]+>)?\s*(?:extends\s+([\w<>, ]+))?\s*\{([^}]*)\}",
        re.DOTALL,
    )
    for match in iface_pattern.finditer(text):
        name = match.group(1)
        extends_raw = (match.group(2) or "").strip()
        body = match.group(3)
        parents: list[str] = []
        if extends_raw:
            for parent in extends_raw.split(","):
                parent = parent.strip()
                parent = re.sub(r"<[^>]+>", "", parent).strip()
                if parent:
                    parents.append(parent)
        fields: list[str] = []
        field_pattern = re.compile(r"^\s*(\w+)\??\s*:", re.MULTILINE)
        for field_name in field_pattern.findall(body):
            fields.append(field_name)
        interfaces[name] = {"fields": fields, "extends": parents}
    return interfaces


def resolve_ts_fields(name: str, interfaces: dict[str, dict], visited: set[str] | None = None) -> list[str]:
    if visited is None:
        visited = set()
    if name in visited or name not in interfaces:
        return []
    visited.add(name)
    info = interfaces[name]
    fields: list[str] = []
    for parent in info["extends"]:
        fields.extend(resolve_ts_fields(parent, interfaces, visited))
    fields.extend(info["fields"])
    seen: set[str] = set()
    deduped: list[str] = []
    for f in fields:
        if f not in seen:
            seen.add(f)
            deduped.append(f)
    return deduped


def load_config() -> dict:
    with CONFIG_PATH.open() as f:
        return yaml.safe_load(f)


def collect(files: list[str], extractor):
    merged: dict = {}
    for rel in files:
        path = REPO_ROOT / rel
        if not path.exists():
            print(f"WARN: missing file {rel}", file=sys.stderr)
            continue
        merged.update(extractor(path.read_text()))
    return merged


def check_pair(
    pair: dict,
    kotlin_classes: dict,
    ts_interfaces: dict,
) -> str | None:
    name = pair["name"]
    kt_name = pair["kotlin_class"]
    ts_name = pair["ts_interface"]

    kt_fields = kotlin_classes.get(kt_name)
    ts_fields_raw = ts_interfaces.get(ts_name)
    ts_fields = resolve_ts_fields(ts_name, ts_interfaces) if ts_fields_raw is not None else None

    if kt_fields is None:
        return f"[{name}] Kotlin data class `{kt_name}` not found"
    if ts_fields is None:
        return f"[{name}] TS interface `{ts_name}` not found"

    kt_set = set(kt_fields)
    ts_set = set(ts_fields)
    only_kt = sorted(kt_set - ts_set)
    only_ts = sorted(ts_set - kt_set)
    if not only_kt and not only_ts:
        return None

    block = [f"[{name}] DTO drift detected:"]
    if only_kt:
        block.append(f"  fields present in Kotlin ({kt_name}) but missing in TS ({ts_name}): {only_kt}")
    if only_ts:
        block.append(f"  fields present in TS ({ts_name}) but missing in Kotlin ({kt_name}): {only_ts}")
    return "\n".join(block)


def main() -> int:
    cfg = load_config()
    kotlin_classes = collect(cfg["kotlin_files"], extract_kotlin_classes)
    ts_interfaces = collect(cfg["ts_files"], extract_ts_interfaces)

    failures = [
        msg
        for pair in cfg["pairs"]
        if (msg := check_pair(pair, kotlin_classes, ts_interfaces)) is not None
    ]

    if failures:
        print("DTO drift check FAILED:\n", file=sys.stderr)
        for f in failures:
            print(f, file=sys.stderr)
            print("", file=sys.stderr)
        return 1

    print(f"DTO drift check PASSED ({len(cfg['pairs'])} pairs verified)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
