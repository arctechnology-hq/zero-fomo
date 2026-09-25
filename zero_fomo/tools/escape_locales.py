#!/usr/bin/env python3
"""Post-process generated values-*/strings.xml: aapt requires ' and " escaped
inside resource text, and app_name (a product name) must not be redefined.
Idempotent; run after translate_strings.py."""
import glob
import os
import re

RES = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "res"))
TEXT_RX = re.compile(r"(<(?:string|item)\b[^>]*>)([^<]*)(</(?:string|item)>)")
APOS_RX = re.compile(r"(?<!\\)'")
QUOTE_RX = re.compile(r'(?<!\\)"')
APP_NAME_RX = re.compile(r'[ \t]*<string name="app_name"[^>]*>[^<]*</string>\r?\n?')


def fix_text(m: re.Match) -> str:
    txt = m.group(2)
    txt = APOS_RX.sub(r"\\'", txt)
    txt = QUOTE_RX.sub(r'\\"', txt)
    return m.group(1) + txt + m.group(3)


for path in sorted(glob.glob(os.path.join(RES, "values-*", "strings.xml"))):
    s = open(path, encoding="utf-8").read()
    before = len(APOS_RX.findall(s)) + len(QUOTE_RX.findall(s))
    s = APP_NAME_RX.sub("", s)
    s = TEXT_RX.sub(fix_text, s)
    after = len(APOS_RX.findall(s)) + len(QUOTE_RX.findall(s))
    open(path, "w", encoding="utf-8", newline="\n").write(s)
    print(f"{os.path.relpath(path, RES)}: escaped {before - after}, app_name removed: {'app_name' not in s}")
