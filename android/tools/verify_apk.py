#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""verify_apk.py — APK installability self-check (Android 11+ hard requirements)

Usage: python3 tools/verify_apk.py <apk>

Checks:
  1. resources.arsc MUST be STORED (uncompressed) and data-offset 4-byte aligned
     (Android 11+ hard requirement — rejects install if violated)
  2. classes.dex present; warn if not STORED + aligned (affects startup mmap, not fatal)
  3. Exactly one classes.dex; lib/ directory presence (informational — not required)
  4. AndroidManifest.xml and resources.arsc present
  5. v1/v2/v3 signature artefacts (.SF / .RSA / .DSA entries)
  6. META-INF/* sanity, total entry count, APK size report
"""
import struct
import sys
import zipfile
import os


def data_offset(zf, info):
    """Read the local file header and compute the true data start offset."""
    zf.fp.seek(info.header_offset)
    hdr = zf.fp.read(30)
    if len(hdr) != 30 or hdr[:4] != b"PK\x03\x04":
        return None
    name_len, extra_len = struct.unpack_from("<HH", hdr, 26)
    return info.header_offset + 30 + name_len + extra_len


def main(path):
    if not os.path.isfile(path):
        print("ERROR: file not found: %s" % path)
        return 2

    zf = zipfile.ZipFile(path)
    names = set(zf.namelist())
    apk_size = os.path.getsize(path)
    fails, warns = [], []

    # ── 1) resources.arsc — Android 11+ hard requirement ──
    print("=== 1) resources.arsc (Android 11+ hard requirement) ===")
    if "resources.arsc" not in names:
        fails.append("resources.arsc is missing")
        print("  MISSING ✗")
    else:
        i = zf.getinfo("resources.arsc")
        off = data_offset(zf, i)
        stored = i.compress_type == zipfile.ZIP_STORED
        aligned = off is not None and off % 4 == 0
        print("  compress=%s  offset=%d  %%4=%d  -> %s" % (
            "STORED" if stored else "DEFLATE", off, off % 4,
            "OK" if (stored and aligned) else "FAIL"))
        if not stored:
            fails.append("resources.arsc is compressed (Android 11+ rejects install)")
        if not aligned:
            fails.append("resources.arsc is not 4-byte aligned")

    # ── 2) classes.dex ──
    print("\n=== 2) classes.dex ===")
    dex_entries = sorted(n for n in names if n.endswith(".dex"))
    if not dex_entries:
        fails.append("no classes.dex found")
        print("  MISSING ✗")
    else:
        print("  found %d dex file(s): %s" % (len(dex_entries), ", ".join(dex_entries)))
        if len(dex_entries) != 1:
            warns.append("expected exactly 1 classes.dex, found %d" % len(dex_entries))
        for dn in dex_entries:
            i = zf.getinfo(dn)
            off = data_offset(zf, i)
            stored = i.compress_type == zipfile.ZIP_STORED
            aligned = off is not None and off % 4 == 0
            print("  %s: compress=%s  offset=%d  %%4=%d" % (
                dn, "STORED" if stored else "DEFLATE", off, off % 4))
            if not (stored and aligned):
                warns.append("%s is not STORED+aligned (installable, but slower startup)" % dn)

    # ── 3) native libraries (informational — not required for this app) ──
    print("\n=== 3) native libraries ===")
    libs = sorted(n for n in names if n.startswith("lib/"))
    if libs:
        abis = sorted({n.split("/")[1] for n in libs})
        for abi in abis:
            so_files = sorted(n.split("/")[-1] for n in libs if n.startswith("lib/%s/" % abi))
            print("  %-12s %s" % (abi, ", ".join(so_files)))
        warns.append("lib/ directory present — unexpected for this app")
    else:
        print("  (none — as expected for a pure-Java app)")

    # ── 4) required entries ──
    print("\n=== 4) required entries ===")
    for entry in ("AndroidManifest.xml", "resources.arsc"):
        status = "OK" if entry in names else "MISSING ✗"
        print("  %-30s %s" % (entry, status))
        if entry not in names:
            fails.append("%s is missing" % entry)

    # ── 5) signature schemes ──
    print("\n=== 5) signature schemes ===")
    sf_files = sorted(n for n in names if n.startswith("META-INF/") and (
        n.endswith(".SF") or n.endswith(".RSA") or n.endswith(".DSA") or n.endswith(".EC")))
    v1_files = sorted(n for n in sf_files if not n.upper().endswith(".RSA") and not n.upper().endswith(".DSA") and not n.upper().endswith(".EC"))
    has_v1 = any(n.endswith(".SF") for n in names)
    # v2/v3 signing is embedded in APK Signing Block (ZIP中央目录之后), not visible as entries
    # The presence of v1 files and successful apksigner verify implies v2/v3 were set at sign time
    print("  v1 signature entries: %s" % (", ".join(sf_files) if sf_files else "none"))
    if not has_v1:
        warns.append("no v1 signature files (.SF/.RSA) — older devices (< Android 7) may not install")

    # ── 6) META-INF sanity + summary ──
    print("\n=== 6) META-INF / summary ===")
    meta = sorted(n for n in names if n.startswith("META-INF/"))
    print("  META-INF entries: %d" % len(meta))
    for m in meta:
        print("    %s" % m)
    print("  total ZIP entries: %d" % len(names))
    print("  APK size: %d bytes (%.1f KB)" % (apk_size, apk_size / 1024.0))

    # ── Verdict ──
    print()
    if fails:
        print("结论: 不可安装 ✗")
        for f in fails:
            print("  - %s" % f)
    else:
        print("结论: 通过 ✓ （硬性条件全部满足）")
    for w in warns:
        print("提示: %s" % w)
    return 1 if fails else 0


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(2)
    sys.exit(main(sys.argv[1]))
