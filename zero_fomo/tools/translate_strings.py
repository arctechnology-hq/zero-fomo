#!/usr/bin/env python3
"""translate_strings.py — generate values-<lang>/strings.xml from the English
resource files on the FIE flash tier (DeepSeek via _FIE/deepseek.ps1), at zero
Claude tokens. Review before shipping; machine output is a first draft.

    python tools/translate_strings.py                 # all languages in LANGS
    python tools/translate_strings.py es fr           # a subset
    python tools/translate_strings.py --check         # validate existing translations only

Every <string> and <plurals> from app/src/main/res/values/strings*.xml is sent
as one XML document per language with a translator system prompt. The reply
is parsed as XML, then checked: same resource names, same count, identical
%1$s / %1$d placeholders per string, no untranslated copies unless the source
is a proper noun. Failures are printed and that language is left unwritten.
"""
from __future__ import annotations

import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))
APP_RES = os.path.normpath(os.path.join(HERE, "..", "app", "src", "main", "res"))
FIE = r"C:\Users\rhanrichardson\OneDrive - National Health Insurance Authority\Documents\AI_Workspaces\_FIE"
WORKER = os.path.join(FIE, "deepseek.ps1")

LANGS = {
    "es": "Spanish (Latin American, neutral; usted-free, informal 'tú' is fine for an events app)",
    "fr": "French (Caribbean / Canadian friendly, neutral register)",
    "pt": "Portuguese (Brazilian)",
    "ht": "Haitian Creole (Kreyòl ayisyen, standard orthography)",
    "nl": "Dutch (Netherlands standard, as used in Aruba / Curaçao / Bonaire)",
}
PROPER_NOUNS = {"0 FOMO", "WhatsApp", "Instagram", "TikTok", "Facebook", "Telegram", "Discord", "Reddit", "Eventbrite", "Ticketmaster", "SeatGeek", "Bahamas", "Nassau", "Junkanoo"}
PLACEHOLDER_RX = re.compile(r"%(\d+\$)?[sdf]")
NOTES: list[str] = []      # informational: strings kept identical to English


def load_english() -> list[tuple[str, str, str]]:
    """[(kind, name, xml_fragment)] for every string/plurals in values/strings*.xml."""
    items = []
    for fn in sorted(os.listdir(os.path.join(APP_RES, "values"))):
        if not (fn.startswith("strings") and fn.endswith(".xml")):
            continue
        tree = ET.parse(os.path.join(APP_RES, "values", fn))
        for el in tree.getroot():
            if el.tag not in ("string", "plurals"):
                continue
            if el.get("translatable") == "false":
                continue
            items.append((el.tag, el.get("name"), ET.tostring(el, encoding="unicode")))
    return items


def build_prompt(lang: str, items: list[tuple[str, str, str]]) -> tuple[str, str]:
    system = (
        "You are a professional app localiser. Translate the Android string resources in the user "
        f"message from English into {LANGS[lang]}. Output ONLY a complete, well-formed XML document: "
        "<?xml version=\"1.0\" encoding=\"utf-8\"?><resources>…</resources>. Keep every resource "
        "name and every <plurals><item quantity=…> structure exactly; add the quantity forms the "
        "target language needs (e.g. 'one' and 'other'). Keep placeholders like %1$s, %1$d, %2$s "
        "exactly as they are and in a natural position. Keep the app name '0 FOMO' and other product "
        "names untranslated. Escape apostrophes as \\' and keep existing \\n. Keep translations short: "
        "these are buttons, chips and labels on a phone screen. Do not add comments or explanations."
    )
    body = "\n".join(frag for _, _, frag in items)
    user = f"<resources>\n{body}\n</resources>"
    return system, user


def call_worker(system: str, prompt: str, out_path: str) -> str:
    cmd = ["pwsh", "-NoProfile", "-File", WORKER, "-Prompt", prompt, "-System", system,
           "-OutFile", out_path, "-MaxTokens", "16000", "-Tier", "flash"]
    res = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=900)
    if res.returncode != 0:
        raise RuntimeError(f"deepseek.ps1 exit {res.returncode}: {res.stderr[-500:]}")
    return open(out_path, encoding="utf-8-sig").read()


def extract_xml(text: str) -> str:
    m = re.search(r"<resources>.*</resources>", text, re.S)
    if not m:
        raise ValueError("no <resources> block in reply")
    return '<?xml version="1.0" encoding="utf-8"?>\n' + m.group(0) + "\n"


def check(lang: str, english: list[tuple[str, str, str]], xml_text: str) -> list[str]:
    problems = []
    try:
        root = ET.fromstring(xml_text.encode("utf-8"))
    except ET.ParseError as exc:
        return [f"XML parse error: {exc}"]
    got = {el.get("name"): el for el in root if el.tag in ("string", "plurals")}
    for kind, name, frag in english:
        if name not in got:
            problems.append(f"missing {kind} {name}")
            continue
        src = ET.fromstring(frag)
        el = got[name]
        if el.tag != kind:
            problems.append(f"{name}: expected <{kind}> got <{el.tag}>")
            continue
        if kind == "string":
            s_ph = sorted(PLACEHOLDER_RX.findall(src.text or ""))
            t_ph = sorted(PLACEHOLDER_RX.findall(el.text or ""))
            if s_ph != t_ph:
                problems.append(f"{name}: placeholders {s_ph} -> {t_ph}")
            src_txt = (src.text or "").strip()
            # Identical output is only a failure for real sentences; single
            # words such as Festival / Outlook / Flyer / Link are the same in
            # most target languages and get a note instead.
            if (el.text or "").strip() == src_txt and src_txt not in PROPER_NOUNS \
                    and len(src_txt.split()) >= 3 and not re.fullmatch(r"[\W\d%$sd]+", src_txt):
                problems.append(f"{name}: untranslated '{src_txt[:40]}'")
            elif (el.text or "").strip() == src_txt and len(src_txt) > 3:
                NOTES.append(f"[{lang}] {name}: kept as '{src_txt[:30]}'")
        else:
            src_ph = sorted(PLACEHOLDER_RX.findall("".join(i.text or "" for i in src)))
            for item in el:
                t_ph = sorted(PLACEHOLDER_RX.findall(item.text or ""))
                if set(t_ph) - set(src_ph):
                    problems.append(f"{name}[{item.get('quantity')}]: unexpected placeholders {t_ph}")
            if not any(i.get("quantity") == "other" for i in el):
                problems.append(f"{name}: plurals without quantity=other")
    extra = set(got) - {n for _, n, _ in english}
    if extra:
        problems.append(f"extra resources not in English: {sorted(extra)[:5]}")
    return problems


def main() -> int:
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    check_only = "--check" in sys.argv
    from_raw = "--from-raw" in sys.argv      # re-validate the last replies without a new tier call
    langs = args or list(LANGS)
    english = load_english()
    print(f"{len(english)} English resources")
    rc = 0
    for lang in langs:
        dest_dir = os.path.join(APP_RES, f"values-{lang}")
        dest = os.path.join(dest_dir, "strings.xml")
        if check_only:
            if not os.path.exists(dest):
                print(f"[{lang}] no translation yet")
                continue
            probs = check(lang, english, open(dest, encoding="utf-8").read())
        else:
            system, prompt = build_prompt(lang, english)
            raw_path = os.path.join(HERE, f".translate_{lang}.raw.txt")
            print(f"[{lang}] {'re-validating last reply' if from_raw else 'translating on the flash tier'} "
                  f"({len(english)} resources)…")
            try:
                reply = (open(raw_path, encoding="utf-8-sig").read() if from_raw
                         else call_worker(system, prompt, raw_path))
                xml_text = extract_xml(reply)
            except Exception as exc:  # noqa: BLE001
                print(f"[{lang}] FAILED: {exc}")
                rc = 1
                continue
            probs = check(lang, english, xml_text)
            if probs:
                print(f"[{lang}] {len(probs)} problem(s); not written:")
            else:
                os.makedirs(dest_dir, exist_ok=True)
                with open(dest, "w", encoding="utf-8", newline="\n") as fh:
                    fh.write(xml_text)
                print(f"[{lang}] wrote {dest}")
        for p in probs:
            print(f"    - {p}")
        if probs:
            rc = 1
    if NOTES:
        print(f"{len(NOTES)} string(s) kept identical to English (review):")
        for n in NOTES:
            print(f"    {n}")
    return rc


if __name__ == "__main__":
    sys.exit(main())
