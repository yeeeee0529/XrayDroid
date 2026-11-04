#!/usr/bin/env python3
"""檢查三個 strings.xml 的資源名稱與順序完全同步。

values/（預設 zh-TW）、values-en/ 與 values-zh-rCN/ 的頂層資源
（string、plurals 等）必須有相同集合且順序一致；plural 內部 item 的
quantity 可依語言文法不同（例如 en 提供 one／other），不在檢查範圍。
離開非零代碼表示不同步；診斷輸出為英文。
"""

from pathlib import Path
import sys
import xml.etree.ElementTree as ET

RES_DIR = Path(__file__).resolve().parent.parent / "app" / "src" / "main" / "res"
FILES = ("values/strings.xml", "values-en/strings.xml", "values-zh-rCN/strings.xml")


def load_entries(relative_path: str) -> list[str]:
    """回傳 (資源種類, name) 的文件順序列表；name 缺失或重複即失敗。"""
    path = RES_DIR / relative_path
    if not path.is_file():
        raise SystemExit(f"Missing string resource file: {path}")
    try:
        root = ET.parse(path).getroot()
    except ET.ParseError as error:
        raise SystemExit(f"{relative_path}: invalid XML: {error}") from error
    entries = []
    for element in root:
        name = element.get("name")
        if not name:
            raise SystemExit(
                f"{relative_path}: resource element <{element.tag}> has no name attribute"
            )
        entries.append((element.tag, name))
    return entries


def main() -> int:
    parsed = {relative: load_entries(relative) for relative in FILES}

    for relative, entries in parsed.items():
        names = [name for _, name in entries]
        if len(set(names)) != len(names):
            duplicates = sorted({name for name in names if names.count(name) > 1})
            print(f"{relative}: duplicate resource names: {', '.join(duplicates)}", file=sys.stderr)
            return 1

    reference_relative, reference = next(iter(parsed.items()))
    count = len(reference)
    for relative, entries in list(parsed.items())[1:]:
        other_names = [name for _, name in entries]
        expected_names = [name for _, name in reference]
        if len(entries) != count:
            missing = sorted(set(expected_names) - set(other_names))
            extra = sorted(set(other_names) - set(expected_names))
            print(
                f"{relative}: resource count {len(entries)} does not match "
                f"{reference_relative}: {count}; missing: {missing or '-'}; extra: {extra or '-'}",
                file=sys.stderr,
            )
            return 1
        for index, (actual, expected) in enumerate(zip(entries, reference)):
            if actual != expected:
                print(
                    f"{relative}: resource {index} is <{actual[0]} name='{actual[1]}'> but "
                    f"{reference_relative} has <{expected[0]} name='{expected[1]}'>; "
                    "names or order are out of sync",
                    file=sys.stderr,
                )
                return 1

    print(
        f"{len(FILES)} string resource files are in sync; {count} resources "
        f"each with matching names and order."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
