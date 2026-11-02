#!/usr/bin/env python3
"""
Draw the charts in the benchmark section of classgraph-vfs/README.md from the results.md that
RunBenchmarks writes, two charts for each content type:

* first-read-<content>.png: MB/s against the number of threads, one panel for each layout, one
  line for each library.
* whole-archive-<content>.png: the time to open an archive, read every entry and close it, one
  panel of bars for each layout.

    python3 classgraph-vfs/src/test/perf/plot_benchmarks.py RESULTS_MD [CHART_DIR]

CHART_DIR defaults to classgraph-vfs/docs/benchmarks. Needs matplotlib. The same results give the
same files, byte for byte.
"""

import re
import sys
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.lines import Line2D
from matplotlib.ticker import FuncFormatter, LogLocator, NullFormatter

FIRST_READ_CAPTION = re.compile(r"^Open, read every entry once in random order, close, (\w+), MB/s:$")
WHOLE_ARCHIVE_CAPTION = re.compile(r"^Open, read every entry, close, (\w+), ms:$")
LAYOUTS = ["dir", "stored", "deflated", "nested-stored", "nested-deflated"]

# Chart ink and surface
SURFACE = "#fcfcfb"
INK = "#0b0b0b"
INK_SECONDARY = "#52514e"
INK_MUTED = "#898781"
GRID = "#e1e0d9"
AXIS = "#c3c2b7"
OTHER_BAR = "#b4b2aa"
CGVFS_COLOR = "#2a78d6"
# The other categorical hues, in their fixed order. There are more libraries than hues, so a library
# is told apart by its hue and its line style together
HUES = ["#eb6834", "#1baf7a", "#eda100", "#e87ba4", "#008300", "#6250d6", "#e34948"]
STYLES = ["-", (0, (4, 2))]

plt.rcParams.update({
    "font.family": "sans-serif",
    "font.size": 10,
    "text.color": INK,
    "axes.edgecolor": AXIS,
    "axes.labelcolor": INK_SECONDARY,
    "axes.titlecolor": INK,
    "axes.titlesize": 11,
    "xtick.color": INK_MUTED,
    "ytick.color": INK_MUTED,
    "xtick.labelcolor": INK_SECONDARY,
    "ytick.labelcolor": INK_SECONDARY,
    "figure.facecolor": SURFACE,
    "axes.facecolor": SURFACE,
})


def split_row(line):
    return [cell.strip() for cell in line.strip().strip("|").split("|")]


def number(cell):
    return float(cell.replace(",", "")) if cell else None


def read_tables(path):
    """{(kind, content): (header, rows)} for every table in results.md, kind being "first" or "whole"."""
    lines = path.read_text(encoding="utf-8").split("\n")
    tables = {}
    for i, line in enumerate(lines):
        match = FIRST_READ_CAPTION.match(line) or WHOLE_ARCHIVE_CAPTION.match(line)
        if not match:
            continue
        kind = "first" if FIRST_READ_CAPTION.match(line) else "whole"
        j = i + 1
        while not lines[j].startswith("|"):
            j += 1
        header = split_row(lines[j])
        rows = []
        j += 2
        while j < len(lines) and lines[j].startswith("|"):
            rows.append(split_row(lines[j]))
            j += 1
        tables[(kind, match.group(1))] = (header, rows)
    return tables


def is_cgvfs(library):
    return library.startswith("classgraph-vfs")


def line_styles(tables):
    """The color and line style of every library in the line charts, the same in every chart."""
    libraries = []
    for (kind, _), (_, rows) in tables.items():
        for row in rows:
            if kind == "first" and row[1] not in libraries:
                libraries.append(row[1])
    styles = {}
    others = [library for library in libraries if not is_cgvfs(library)]
    for library in libraries:
        if library == "classgraph-vfs":
            styles[library] = dict(color=CGVFS_COLOR, linestyle="-", linewidth=2.6, zorder=4)
        elif is_cgvfs(library):
            styles[library] = dict(color=CGVFS_COLOR, linestyle=STYLES[1], linewidth=2.0, zorder=3)
        else:
            i = others.index(library)
            styles[library] = dict(color=HUES[i % len(HUES)], linestyle=STYLES[i // len(HUES)], linewidth=1.4,
                                   zorder=2)
    return styles


def save(fig, path):
    path.parent.mkdir(parents=True, exist_ok=True)
    # No metadata, so the same numbers give the same file
    fig.savefig(path, dpi=100, facecolor=SURFACE, metadata={"Software": None})
    plt.close(fig)


def first_read_chart(content, header, rows, styles, out_dir):
    threads = [int(h.split()[0]) for h in header[2:]]
    fig, axes = plt.subplots(2, 3, figsize=(12, 7.5), sharey=True)
    libraries = []
    for ax, layout in zip(axes.flat, LAYOUTS):
        for row in (r for r in rows if r[0] == layout):
            points = [(t, number(c)) for t, c in zip(threads, row[2:]) if number(c) is not None]
            ax.plot([p[0] for p in points], [p[1] for p in points], marker="o", markersize=4, **styles[row[1]])
            if row[1] not in libraries:
                libraries.append(row[1])
        ax.set_title(layout, loc="left")
        ax.set_xscale("log", base=2)
        ax.set_xticks(threads, [str(t) for t in threads])
        ax.minorticks_off()
        ax.set_yscale("log")
        ax.yaxis.set_major_locator(LogLocator(base=10, subs=(1, 2, 5)))
        ax.yaxis.set_major_formatter(FuncFormatter(lambda v, _: f"{v:,.0f}"))
        ax.yaxis.set_minor_formatter(NullFormatter())
        ax.grid(True, which="major", color=GRID, linewidth=0.8)
        ax.set_axisbelow(True)
        for side in ("top", "right"):
            ax.spines[side].set_visible(False)
    for ax in axes[1]:
        ax.set_xlabel("Threads")
    for ax in axes[:, 0]:
        ax.set_ylabel("MB/s (log scale)")
    # The sixth panel holds the legend
    legend_ax = axes.flat[5]
    legend_ax.axis("off")
    legend_ax.legend([Line2D([], [], marker="o", markersize=4, **styles[library]) for library in libraries],
                     libraries, loc="center left", frameon=False, handlelength=3)
    fig.suptitle(f"Every entry read once, in random order, {content}: MB/s, higher is better", x=0.01,
                 ha="left", fontsize=13)
    fig.tight_layout()
    save(fig, out_dir / f"first-read-{content}.png")


def whole_archive_chart(content, header, rows, out_dir):
    fig, axes = plt.subplots(2, 3, figsize=(12, 7.5))
    for ax, layout in zip(axes.flat, LAYOUTS):
        col = header.index(layout)
        bars = sorted(((number(row[col]), row[0]) for row in rows if number(row[col]) is not None),
                      reverse=True)
        ys = range(len(bars))
        ax.barh(ys, [b[0] for b in bars], height=0.7,
                color=[CGVFS_COLOR if is_cgvfs(b[1]) else OTHER_BAR for b in bars])
        ax.set_yticks(ys, [b[1] for b in bars])
        for label, (_, library) in zip(ax.get_yticklabels(), bars):
            if is_cgvfs(library):
                label.set_fontweight("bold")
                label.set_color(INK)
        top = max(b[0] for b in bars)
        for y, (ms, _) in zip(ys, bars):
            ax.text(ms + top * 0.01, y, f"{ms:,.1f}", va="center", fontsize=8.5, color=INK_SECONDARY)
        ax.set_xlim(0, top * 1.18)
        ax.set_title(layout, loc="left")
        ax.tick_params(axis="y", length=0)
        ax.grid(True, axis="x", color=GRID, linewidth=0.8)
        ax.set_axisbelow(True)
        for side in ("top", "right", "left"):
            ax.spines[side].set_visible(False)
    for ax in axes[1]:
        ax.set_xlabel("ms")
    axes.flat[5].axis("off")
    fig.suptitle(f"Open, read every entry, close, {content}: ms, shorter is better", x=0.01, ha="left",
                 fontsize=13)
    fig.tight_layout()
    save(fig, out_dir / f"whole-archive-{content}.png")


def main():
    results = Path(sys.argv[1])
    out_dir = Path(sys.argv[2]) if len(sys.argv) > 2 else Path(__file__).resolve().parents[3] / "docs" / "benchmarks"
    tables = read_tables(results)
    styles = line_styles(tables)
    for (kind, content), (header, rows) in tables.items():
        if kind == "first":
            first_read_chart(content, header, rows, styles, out_dir)
        else:
            whole_archive_chart(content, header, rows, out_dir)


if __name__ == "__main__":
    main()
