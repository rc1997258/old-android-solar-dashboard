#!/usr/bin/env python3
"""
Scale hard-coded dimensions in an apktool-decoded APK so layouts fit a small
(240x320, ldpi) screen like the Galaxy Mini.

Edits files IN PLACE and compounds if run twice, so `git init && git commit`
the decoded folder first, and `git checkout .` before re-running with a new factor.

Usage:
    python3 scale_res.py soyo_src --dry-run
    python3 scale_res.py soyo_src --factor 0.8 --text-factor 0.85
"""
import argparse
import pathlib
import re

# Matches 16dp, 16.0dip, 14sp, -4.0dip, 2px ... but not things like #ff00ff or abc12dp
DIM = re.compile(r'(?<![\w.#])(-?\d+(?:\.\d+)?)(dip|dp|sp|px)(?!\w)')


def scale(match, factor, text_factor):
    value, unit = float(match.group(1)), match.group(2)
    if abs(value) <= 1:  # keep hairlines/dividers visible
        return match.group(0)
    f = text_factor if unit == "sp" else factor
    return f"{round(value * f, 1):g}{unit}"


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("src", help="apktool output folder (contains res/)")
    ap.add_argument("--factor", type=float, default=0.8,
                    help="multiplier for dp/dip/px sizes (default 0.8)")
    ap.add_argument("--text-factor", type=float, default=None,
                    help="multiplier for sp text sizes (default: same as --factor)")
    ap.add_argument("--dry-run", action="store_true", help="report only, change nothing")
    a = ap.parse_args()
    text_factor = a.text_factor or a.factor

    res = pathlib.Path(a.src) / "res"
    targets = list(res.glob("layout*/*.xml"))
    for name in ("dimens.xml", "styles.xml"):
        targets += res.glob(f"values*/{name}")

    total = 0
    for path in sorted(targets):
        text = path.read_text(encoding="utf-8")
        count = 0

        def repl(m):
            nonlocal count
            out = scale(m, a.factor, text_factor)
            if out != m.group(0):
                count += 1
            return out

        new = DIM.sub(repl, text)
        if count:
            total += count
            print(f"{count:5d}  {path.relative_to(a.src)}")
            if not a.dry_run:
                path.write_text(new, encoding="utf-8")

    print(f"{'Would change' if a.dry_run else 'Changed'} {total} values in total")


if __name__ == "__main__":
    main()
