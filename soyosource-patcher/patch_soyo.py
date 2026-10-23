#!/usr/bin/env python3
"""
Patch the apktool-decoded Soyosource (E4A) app for the Galaxy Mini (240x320):

  1. Font scale (--font-scale): multiplies every font size given to labels,
     buttons and edit boxes (patched once in the E4A runtime).
  2. Stretch (--stretch): E4A lays out every box from the "screen height"
     function; we make it report N x the real height, so all boxes become
     N x taller. Windows are made scrollable so you can swipe to the rest.
  3. One column (skip with --two-columns): re-lays out the AP dashboard
     (窗口13) so each of the 10 readings gets its own full-width row;
     the Error row and the Reboot/Settings buttons keep their size and
     style and move down below the list. The 10 value boxes are made solid
     (the app drew them at ~40% opacity) and given one text colour.
  4. AP-only (skip with --no-ap): main window skips its startup and, after
     --ap-delay ms, switches to 窗口13 (English AP dashboard, 192.168.4.1:10000).

Safe to re-run: it detects and replaces its own earlier patches.

Usage:
    python3 patch_soyo.py soyo_src --stretch 2 --font-scale 1.0
    python3 patch_soyo.py soyo_src --stretch 1.5 --font-scale 0.9 --ap-delay 1500
    python3 patch_soyo.py soyo_src --row-pitch 0.07       # tighter rows
    python3 patch_soyo.py soyo_src --row-height 0.045 --row-pitch 0.045   # slim rows, touching
    python3 patch_soyo.py soyo_src --value-color 000000   # black values
"""
import argparse
import pathlib
import re
import struct
import sys

RT = "e4a/runtime"
FONT_FILES = [
    f"{RT}/components/impl/android/n3/标签Impl.smali",    # label
    f"{RT}/components/impl/android/n1/按钮Impl.smali",    # button
    f"{RT}/components/impl/android/n2/编辑框Impl.smali",  # edit box
]
SYS_FILE = f"{RT}/系统相关类.smali"
APP_FILE = f"{RT}/应用操作.smali"
MAIN = "soysource/主窗口.smali"
W13 = "soysource/窗口13.smali"
LAUNCHER = "soysource/APLauncher.smali"

FONT_MARK = "# FONTSCALE"
HEIGHT_SIG = ".method public static 取屏幕高度_不含导航栏()I\n"
SWITCH_SIG = ".method public static 切换窗口(Lcom/e4a/runtime/components/窗口;)V\n"

# ---------------------------------------------------------------- AP-only
OLD_CALL = re.compile(
    r"    # APONLY[^\n]*\n"
    r"    invoke-virtual \{p0\}, Lcom/soysource/主窗口;->(?:打开AP窗口|延迟打开AP)\(\)V\n"
    r"(?:\n    return-void\n)?")

CALL_V3 = ("    # APONLY v3: schedule switch to 窗口13, skip the rest of the main startup\n"
           "    invoke-virtual {p0}, Lcom/soysource/主窗口;->延迟打开AP()V\n\n"
           "    return-void\n")

OPEN_METHOD = r"""
.method public 打开AP窗口()V
    .locals 2

    # APONLY: open 窗口13 (copied from the app's own menu handler)
    const-string v0, "\u7a97\u53e313"

    invoke-static {v0}, Lcom/e4a/runtime/应用操作;->读取窗口(Ljava/lang/String;)Lcom/e4a/runtime/components/impl/android/窗口Impl;

    move-result-object v1

    if-nez v1, :ap_have_window

    new-instance v1, Lcom/soysource/窗口13;

    invoke-direct {v1}, Lcom/soysource/窗口13;-><init>()V

    check-cast v1, Lcom/e4a/runtime/components/impl/android/窗口Impl;

    invoke-static {v0, v1}, Lcom/e4a/runtime/应用操作;->保存窗口(Ljava/lang/String;Lcom/e4a/runtime/components/impl/android/窗口Impl;)V

    :ap_have_window
    check-cast v1, Lcom/e4a/runtime/components/窗口;

    invoke-static {v1}, Lcom/e4a/runtime/应用操作;->切换窗口(Lcom/e4a/runtime/components/窗口;)V

    return-void
.end method
"""

DELAY_METHOD = """
.method public 延迟打开AP()V
    .locals 4

    # APONLY: post APLauncher to the UI thread after a delay
    new-instance v0, Landroid/os/Handler;

    invoke-static {}, Landroid/os/Looper;->getMainLooper()Landroid/os/Looper;

    move-result-object v1

    invoke-direct {v0, v1}, Landroid/os/Handler;-><init>(Landroid/os/Looper;)V

    new-instance v1, Lcom/soysource/APLauncher;

    invoke-direct {v1, p0}, Lcom/soysource/APLauncher;-><init>(Lcom/soysource/主窗口;)V

    const-wide/32 v2, DELAY

    invoke-virtual {v0, v1, v2, v3}, Landroid/os/Handler;->postDelayed(Ljava/lang/Runnable;J)Z

    return-void
.end method
"""

LAUNCHER_CLASS = """.class public Lcom/soysource/APLauncher;
.super Ljava/lang/Object;
.source "APLauncher.java"

# interfaces
.implements Ljava/lang/Runnable;


# instance fields
.field private final w:Lcom/soysource/主窗口;


# direct methods
.method public constructor <init>(Lcom/soysource/主窗口;)V
    .locals 0

    invoke-direct {p0}, Ljava/lang/Object;-><init>()V

    iput-object p1, p0, Lcom/soysource/APLauncher;->w:Lcom/soysource/主窗口;

    return-void
.end method

# Called at the start of every window switch: turn on E4A's built-in
# scrolling for the window. Any failure is swallowed so it can never crash.
.method public static makeScrollable(Lcom/e4a/runtime/components/窗口;)V
    .locals 1

    :try_start_0
    instance-of v0, p0, Lcom/e4a/runtime/components/impl/android/窗口Impl;

    if-eqz v0, :done

    check-cast p0, Lcom/e4a/runtime/components/impl/android/窗口Impl;

    const/4 v0, 0x1

    invoke-virtual {p0, v0}, Lcom/e4a/runtime/components/impl/android/窗口Impl;->可否滚动(Z)V
    :try_end_0
    .catch Ljava/lang/Exception; {:try_start_0 .. :try_end_0} :done

    :done
    return-void
.end method


# fraction of screen width -> pixels
.method public static fw(F)I
    .locals 1

    invoke-static {}, Lcom/e4a/runtime/系统相关类;->取屏幕宽度()I

    move-result v0

    int-to-float v0, v0

    mul-float/2addr v0, p0

    float-to-int v0, v0

    return v0
.end method

# fraction of usable screen height (same formula as the app, so --stretch applies) -> pixels
.method public static fh(F)I
    .locals 2

    invoke-static {}, Lcom/e4a/runtime/系统相关类;->取屏幕高度_不含导航栏()I

    move-result v0

    invoke-static {}, Lcom/e4a/runtime/系统相关类;->取状态栏高度()I

    move-result v1

    sub-int/2addr v0, v1

    int-to-float v0, v0

    mul-float/2addr v0, p0

    float-to-int v0, v0

    return v0
.end method


# virtual methods
.method public run()V
    .locals 1

    iget-object v0, p0, Lcom/soysource/APLauncher;->w:Lcom/soysource/主窗口;

    invoke-virtual {v0}, Lcom/soysource/主窗口;->打开AP窗口()V

    return-void
.end method
"""


def die(msg):
    sys.exit(f"ERROR: {msg}")


def float_hex(f):
    return "0x%08x" % struct.unpack(">I", struct.pack(">f", f))[0]


def method_bounds(text, sig, path):
    start = text.find(sig)
    if start < 0:
        die(f"{sig.strip()} not found in {path}")
    end = text.find(".end method", start)
    return start, end


def first_code_pos(text, start):
    """Position of the first instruction in a method (after .locals/.param/annotations)."""
    pos = text.index("\n", start) + 1
    depth = 0
    while True:
        eol = text.index("\n", pos)
        line = text[pos:eol].strip()
        if depth:
            if line.startswith(".end annotation"):
                depth -= 1
        elif line.startswith(".annotation"):
            depth += 1
        elif not (line == "" or line.startswith((".locals", ".registers", ".param", ".end param"))):
            return pos
        pos = eol + 1


# ---------------------------------------------------------------- patches
def patch_font(root, scale):
    for rel in FONT_FILES:
        path = root / rel
        text = path.read_text(encoding="utf-8")
        line = f"const v0, {float_hex(scale)}    {FONT_MARK} {scale}"
        if FONT_MARK in text:
            text = re.sub(r"const v0, 0x[0-9a-f]+    # FONTSCALE.*", line, text)
            action = "updated"
        else:
            m = re.search(r"\.method public 字体大小\(F\)V\n\s*\.locals (\d+)\n", text)
            if not m:
                die(f"字体大小(F)V not found in {path}")
            if int(m.group(1)) < 1:
                die(f"{path}: method has no local registers to use")
            # v0 is overwritten by getView() right after, so it is free here.
            insert = f"\n    {line}\n\n    mul-float/2addr p1, v0\n"
            text = text[:m.end()] + insert + text[m.end():]
            action = "patched"
        path.write_text(text, encoding="utf-8")
        print(f"font x{scale}: {action} {path.name}")


def patch_stretch(root, stretch):
    path = root / SYS_FILE
    text = path.read_text(encoding="utf-8")
    text = re.sub(r"    # STRETCH[^\n]*\n    mul-int/lit16 v0, v0, 0x[0-9a-f]+\n"
                  r"    div-int/lit16 v0, v0, 0x64\n\n", "", text)
    start, end = method_bounds(text, HEIGHT_SIG, path)
    ret = text.rfind("    return v0\n", start, end)
    if ret < 0:
        die(f"'return v0' not found in 取屏幕高度_不含导航栏 ({path})")
    pct = round(stretch * 100)
    block = (f"    # STRETCH x{stretch}\n"
             f"    mul-int/lit16 v0, v0, {hex(pct)}\n"
             f"    div-int/lit16 v0, v0, 0x64\n\n")
    text = text[:ret] + block + text[ret:]
    path.write_text(text, encoding="utf-8")
    print(f"stretch: screen height reported x{stretch} ({path.name})")


def patch_scroll(root):
    path = root / APP_FILE
    text = path.read_text(encoding="utf-8")
    if "# SCROLLPATCH" in text:
        print(f"scroll: already patched {path.name}")
        return
    start, _ = method_bounds(text, SWITCH_SIG, path)
    pos = first_code_pos(text, start)
    call = ("    # SCROLLPATCH: make every window scrollable before showing it\n"
            "    invoke-static {p0}, Lcom/soysource/APLauncher;->makeScrollable(Lcom/e4a/runtime/components/窗口;)V\n\n")
    text = text[:pos] + call + text[pos:]
    path.write_text(text, encoding="utf-8")
    print(f"scroll: patched 切换窗口 in {path.name}")


# Original layout (fractions of width / usable height), read from 窗口13 $define:
#   rows at top 0.138, 0.233, 0.327, 0.422, 0.525 (height 0.062)
#   left pair  : title 标签16/4/5/6/7  + value 编辑框1-5
#   right pair : title 标签8/9/10/17/18 + value 编辑框6-10
#   标签3 = white panel behind rows (top 0.099), Error row 标签11+编辑框11 (top 0.629)
#   标签14 = panel behind buttons (top 0.754), 按钮1 Reboot / 按钮3 Settings (top 0.791)
ROWS = [  # reading order: left then right, row by row
    ("标签16", "编辑框1"),   # DC Voltage
    ("标签8",  "编辑框6"),   # DC Current
    ("标签4",  "编辑框2"),   # AC Voltage
    ("标签9",  "编辑框7"),   # AC Frequency
    ("标签5",  "编辑框3"),   # Real Power
    ("标签10", "编辑框8"),   # Temp
    ("标签6",  "编辑框4"),   # Total
    ("标签17", "编辑框9"),   # Limiter
    ("标签7",  "编辑框5"),   # Work Mode
    ("标签18", "编辑框10"),  # Status
]
TYPES = {"标签": "n3/标签", "编辑框": "n2/编辑框", "按钮": "n1/按钮"}


def comp_type(name):
    for prefix, t in TYPES.items():
        if name.startswith(prefix):
            return f"Lcom/e4a/runtime/components/impl/android/{t};"
    die(f"unknown component {name}")


ERR_H = 0.062  # Error row keeps the app's original height


def layout_plan(pitch, row_h=0.062):
    top0 = 0.138
    plan = []  # (component, property, fraction)
    for i, (title, value) in enumerate(ROWS):
        top = top0 + i * pitch
        plan += [(title, "左边", 0.038), (title, "顶边", top), (title, "宽度", 0.38), (title, "高度", row_h),
                 (value, "左边", 0.42),  (value, "顶边", top), (value, "宽度", 0.54), (value, "高度", row_h)]
    err_top = top0 + len(ROWS) * pitch + 0.012
    plan += [("标签11", "顶边", err_top), ("编辑框11", "顶边", err_top)]          # Error row: only moved
    plan += [("标签3", "高度", err_top + ERR_H + 0.03 - 0.099)]                  # white panel: fits list
    btn_panel = err_top + ERR_H + 0.06
    plan += [("标签14", "顶边", btn_panel),                                     # button panel: only moved
             ("按钮1", "顶边", btn_panel + 0.037), ("按钮3", "顶边", btn_panel + 0.037)]
    return plan


def signed_hex(n):
    n &= 0xffffffff
    if n >= 0x80000000:
        n -= 0x100000000
    return f"-{hex(-n)}" if n < 0 else hex(n)


def patch_one_column(root, pitch, enable=True, value_color=0xC06000, value_opacity=255, row_h=0.062):
    path = root / W13
    text = path.read_text(encoding="utf-8")
    # remove an earlier version of this patch
    text = text.replace("    # ONECOL\n    invoke-virtual/range {p0 .. p0}, Lcom/soysource/窗口13;->单列布局()V\n\n", "")
    text = re.sub(r"\n\.method public 单列布局\(\)V\n.*?\.end method\n", "\n", text, flags=re.S)

    if not enable:
        path.write_text(text, encoding="utf-8")
        print("one column: off (original 2-per-row layout)")
        return

    start, end = method_bounds(text, ".method public $define()V\n", path)
    ret = text.rfind("    return-void\n", start, end)
    if ret < 0:
        die("return-void not found in 窗口13 $define")
    # p0 is a high register in $define (.locals 20), so the /range form is required
    call = "    # ONECOL\n    invoke-virtual/range {p0 .. p0}, Lcom/soysource/窗口13;->单列布局()V\n\n"
    text = text[:ret] + call + text[ret:]

    body = [".method public 单列布局()V", "    .locals 3", "",
            "    # ONECOL: one reading per row; Error row + buttons moved below the list"]
    for comp, prop, frac in layout_plan(pitch, row_h):
        t = comp_type(comp)
        helper = "fw" if prop in ("左边", "宽度") else "fh"
        body += ["", f"    iget-object v0, p0, Lcom/soysource/窗口13;->{comp}:{t}", "",
                 f"    const v1, {float_hex(frac)}    # {frac:.3f}", "",
                 f"    invoke-static {{v1}}, Lcom/soysource/APLauncher;->{helper}(F)I", "",
                 "    move-result v2", "",
                 f"    invoke-interface {{v0, v2}}, {t}->{prop}(I)V"]
    # Value boxes: the app draws them at opacity 100/255, which makes the text
    # faint and causes ghosting while scrolling. Make them solid, one colour.
    argb = 0xFF000000 | value_color
    body += ["", f"    # ONECOL: value boxes opacity {value_opacity}, text colour #{value_color:06X}"]
    for _, value in ROWS:
        t = comp_type(value)
        body += ["", f"    iget-object v0, p0, Lcom/soysource/窗口13;->{value}:{t}", "",
                 f"    const/16 v1, {hex(value_opacity)}", "",
                 f"    invoke-interface {{v0, v1}}, {t}->透明度(I)V", "",
                 f"    const v1, {signed_hex(argb)}    # #{argb:08X}", "",
                 f"    invoke-interface {{v0, v1}}, {t}->字体颜色(I)V"]
    body += ["", "    return-void", ".end method", ""]
    text = text.rstrip("\n") + "\n\n" + "\n".join(body)
    path.write_text(text, encoding="utf-8")
    print(f"one column: 窗口13 re-laid out, {len(ROWS)} rows, row height {row_h}, row pitch {pitch}, "
          f"values #{value_color:06X} at opacity {value_opacity}")


def patch_ap(root, delay_ms):
    path = root / MAIN
    text = path.read_text(encoding="utf-8")
    text, removed = OLD_CALL.subn("", text)
    m = re.search(r"\.method public 主窗口\$创建完毕\(\)V\n\s*\.locals \d+\n", text)
    if not m:
        die(f"主窗口$创建完毕()V not found in {path}")
    text = text[:m.end()] + "\n" + CALL_V3 + text[m.end():]
    if ".method public 打开AP窗口()V" not in text:
        text = text.rstrip("\n") + "\n" + OPEN_METHOD
    text = re.sub(r"\n\.method public 延迟打开AP\(\)V\n.*?\.end method\n", "\n", text, flags=re.S)
    text = text.rstrip("\n") + "\n" + DELAY_METHOD.replace("DELAY", hex(delay_ms))
    path.write_text(text, encoding="utf-8")
    note = "replaced earlier patch" if removed else "new patch"
    print(f"AP-only v3: {path.name} ({note}), switch after {delay_ms} ms")


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("src", nargs="?", default="soyo_src", help="apktool output folder")
    ap.add_argument("--font-scale", type=float, default=1.0,
                    help="font size multiplier (default 1.0)")
    ap.add_argument("--stretch", type=float, default=2.0,
                    help="make every box N x taller, with scrolling (default 2.0; 1 = off)")
    ap.add_argument("--ap-delay", type=int, default=800,
                    help="ms to wait before switching to 窗口13 (default 800)")
    ap.add_argument("--no-ap", action="store_true", help="skip the AP-only startup patch")
    ap.add_argument("--two-columns", action="store_true",
                    help="keep the original 2-per-row dashboard layout")
    ap.add_argument("--row-height", type=float, default=0.062,
                    help="height of each reading's row, fraction of usable height (default 0.062, the app's own)")
    ap.add_argument("--row-pitch", type=float, default=None,
                    help="distance from one row's top to the next; equal to --row-height means rows touch "
                         "(default: row height + 0.018)")
    ap.add_argument("--value-color", default="C06000",
                    help="text colour for the 10 value boxes, hex RRGGBB (default C06000, the Real Power brown)")
    ap.add_argument("--value-opacity", type=int, default=255,
                    help="opacity of the value boxes 0-255 (app default was 100; default 255 = solid)")
    a = ap.parse_args()
    try:
        value_color = int(a.value_color.lstrip("#"), 16)
        assert 0 <= value_color <= 0xFFFFFF and 0 <= a.value_opacity <= 255
    except (ValueError, AssertionError):
        die("--value-color must be RRGGBB hex and --value-opacity 0-255")

    if not 1.0 <= a.stretch <= 5.0:
        die("--stretch must be between 1 and 5")

    root = pathlib.Path(a.src) / "smali" / "com"
    if not root.is_dir():
        die(f"{root} not found - pass the apktool output folder")

    (root / LAUNCHER).write_text(LAUNCHER_CLASS, encoding="utf-8")
    patch_font(root, a.font_scale)
    patch_stretch(root, a.stretch)
    patch_scroll(root)
    if not 0.02 <= a.row_height <= 0.2:
        die("--row-height must be between 0.02 and 0.2")
    pitch = a.row_pitch if a.row_pitch is not None else round(a.row_height + 0.018, 4)
    patch_one_column(root, pitch, enable=not a.two_columns,
                     value_color=value_color, value_opacity=a.value_opacity, row_h=a.row_height)
    if not a.no_ap:
        patch_ap(root, a.ap_delay)


if __name__ == "__main__":
    main()
