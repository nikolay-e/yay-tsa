#!/usr/bin/env python3
"""
Pre-commit hook to automatically remove emoji from code files.

Removes emoji with smart space handling:
- Priority 1: emoji + trailing spaces
- Priority 2: emoji + leading spaces (if no trailing)
- Priority 3: emoji only (if no spaces)
"""

import argparse
import sys
from pathlib import Path
from typing import List, Optional, Sequence, Set

import emoji
from emoji import EmojiMatch


def parse_whitelist(allow_emoji_args: List[str]) -> Set[str]:
    """
    Parse whitelist arguments into set of emoji characters.

    Args:
        allow_emoji_args: List of emoji (as characters or shortcodes)

    Returns:
        Set of whitelisted emoji characters
    """
    whitelist = set()
    for item in allow_emoji_args:
        if item.startswith(":") and item.endswith(":"):
            converted = emoji.emojize(item)
            whitelist.add(converted)
        else:
            whitelist.add(item)
    return whitelist


def remove_emoji_with_spaces(
    text: str, emoji_char: str, start_pos: int, end_pos: int
) -> str:
    """
    Remove emoji with smart space handling.

    Priority:
    1. Remove emoji + trailing space(s)
    2. Remove emoji + leading space(s) if no trailing
    3. Remove emoji only if no spaces

    Args:
        text: The text containing emoji
        emoji_char: The emoji character(s) to remove
        start_pos: Start position of emoji in text
        end_pos: End position of emoji in text (exclusive)

    Returns:
        Text with emoji and appropriate spaces removed
    """
    trailing_spaces = 0
    pos = end_pos
    while pos < len(text) and text[pos] == " ":
        trailing_spaces += 1
        pos += 1

    if trailing_spaces > 0:
        return text[:start_pos] + text[end_pos + trailing_spaces :]

    leading_spaces = 0
    pos = start_pos - 1
    while pos >= 0 and text[pos] == " ":
        leading_spaces += 1
        pos -= 1

    if leading_spaces > 0:
        return text[: start_pos - leading_spaces] + text[end_pos:]

    return text[:start_pos] + text[end_pos:]


def fix_file(filepath: Path, whitelist: Set[str]) -> bool:
    """
    Fix file in-place by removing emoji and surrounding spaces.

    Args:
        filepath: Path to file to fix
        whitelist: Set of whitelisted emoji to preserve

    Returns:
        True if modifications were made, False otherwise
    """
    try:
        with open(filepath, encoding="utf-8") as f:
            lines = f.readlines()
    except (UnicodeDecodeError, PermissionError):
        return False

    modified = False
    fixed_lines = []

    for line in lines:
        emoji_matches = list(emoji.analyze(line))

        if emoji_matches:
            for match in reversed(emoji_matches):
                emoji_char = match.chars

                if emoji_char in whitelist:
                    continue

                if isinstance(match.value, EmojiMatch):
                    start = match.value.start
                    end = match.value.end

                    line = remove_emoji_with_spaces(line, emoji_char, start, end)
                    modified = True

        fixed_lines.append(line)

    if modified:
        with open(filepath, "w", encoding="utf-8") as f:
            f.writelines(fixed_lines)

    return modified


def main(argv: Optional[Sequence[str]] = None) -> int:
    """
    Main entry point for no-emoji hook.

    Automatically fixes files by removing emoji and surrounding spaces.
    Returns 1 if any files were modified, 0 otherwise.
    """
    parser = argparse.ArgumentParser(
        description="Automatically remove emoji from code files",
        epilog="Returns 1 if files modified, 0 otherwise",
    )
    parser.add_argument(
        "filenames",
        nargs="*",
        help="Filenames to check and fix",
    )
    parser.add_argument(
        "--allow-emoji",
        "-a",
        action="append",
        default=[],
        dest="allow_emoji",
        help="Allow specific emoji (can be used multiple times). Accepts emoji characters or shortcodes like :check_mark:",
    )
    args = parser.parse_args(argv)

    if not args.filenames:
        return 0

    whitelist = parse_whitelist(args.allow_emoji)

    modified_files = []

    for filename in args.filenames:
        filepath = Path(filename)
        was_modified = fix_file(filepath, whitelist)

        if was_modified:
            modified_files.append(filename)

    if modified_files:
        print(
            f"Fixed {len(modified_files)} file(s) by removing emoji:", file=sys.stderr
        )
        for filename in modified_files:
            print(f"  {filename}", file=sys.stderr)
        return 1

    return 0


if __name__ == "__main__":
    sys.exit(main())
