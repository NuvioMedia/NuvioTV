"""Standalone plot of measured HTTP startup reads; no first-frame estimates."""
import argparse
import csv
import json
from pathlib import Path
from statistics import mean, median

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

p = argparse.ArgumentParser()
p.add_argument("--log", required=True)
p.add_argument("--out", required=True)
args = p.parse_args()
out = Path(args.out)
out.mkdir(parents=True, exist_ok=True)
rows = [json.loads(line.split("STARTUP_RESULT ", 1)[1]) for line in Path(args.log).read_text().splitlines() if "STARTUP_RESULT " in line]
assert len(rows) == 48, f"Expected complete 48-trial matrix, got {len(rows)}"
cases = [(net, rar) for net in ("100mbit_40ms", "400mbit_80ms") for rar in (False, True)]
stages = ["session_ms", "head_ms", "cues_ms", "buffer_ms"]
names = ["Open session", "Read head (64 KiB)", "Read Cues / discover RAR", "Return + buffer (2 MiB)"]
palette = ["#577bca", "#d69425", "#8254ad", "#008a78"]
groups = {(net, rar, fast): [r for r in rows if (r["network"], r["archive"], r["fast"]) == (net, rar, fast)] for net, rar in cases for fast in (False, True)}
assert all(len(v) == 6 for v in groups.values())
summary = []
for net, rar in cases:
    off, on = (median(r["total_ms"] for r in groups[net, rar, fast]) for fast in (False, True))
    summary.append(dict(network=net, archive=rar, off_ms=off, on_ms=on, saved_ms=off-on, saved_percent=(off-on)*100/off))
(out / "native-summary.json").write_text(json.dumps(summary, indent=2)+"\n")
(out / "native-startup.jsonl").write_text("\n".join(json.dumps(r) for r in rows)+"\n")
flat = [{k:v for k,v in r.items() if k != "diagnostics"} | {
    "archive_discovery_ms":r["diagnostics"].get("archiveDiscoveryMs"),
    "archive_wait_ms":r["diagnostics"].get("archiveWaitMs"),
    "article_slabs_bytes":r["diagnostics"]["articleSlabsBytes"], **r["diagnostics"]["store"]} for r in rows]
with (out / "native-startup.csv").open("w", newline="", encoding="utf-8") as f:
    w = csv.DictWriter(f, fieldnames=list(flat[0])); w.writeheader(); w.writerows(flat)

plt.rcParams.update({"font.family":"DejaVu Sans", "font.size":10,
    "figure.facecolor":"#f7f9fc", "axes.facecolor":"#f7f9fc", "text.color":"#17263c",
    "axes.labelcolor":"#17263c", "axes.spines.top":False, "axes.spines.right":False,
    "axes.spines.left":False, "axes.spines.bottom":False})
fig, axes = plt.subplots(2, 1, figsize=(13, 11))
fig.suptitle("Fast MKV Startup · measured engine reads", x=.05, y=.97, ha="left", fontsize=22, fontweight="bold")
fig.text(.05,.935,"Controlled TCP NNTP · fresh sessions · 6 OFF + 6 ON trials per case · generated MKV with EOF Cues",color="#536279")
fig.text(.05,.909,"Measures head → Cues → 2 MiB buffer. These are not rendered-first-frame or physical-TV timings.",color="#536279")
for i, (net, rar) in enumerate(cases):
    for fast in (False, True):
        y = i*2.7 + int(fast)
        values = [r["total_ms"] for r in groups[net,rar,fast]]
        m = median(values)
        axes[0].barh(y,m,height=.7,color="#008a78" if fast else "#8696b0")
        axes[0].scatter(values,[y]*len(values),s=18,color="#17263c",alpha=.65,zorder=3)
        axes[0].text(m+20,y,f"{m:,.0f} ms",va="center",fontsize=9)
        left = 0
        for j, key in enumerate(stages):
            d = mean(r[key] for r in groups[net,rar,fast])
            axes[1].barh(y,d,left=left,height=.7,color=palette[j],label=names[j] if i==0 and not fast else None)
            if d>55: axes[1].text(left+d/2,y,f"{d:.0f}",ha="center",va="center",fontsize=8,color="white")
            left += d
        axes[1].text(left+20,y,f"{left:,.0f} ms",va="center",fontsize=9)
    s = summary[i]
    axes[0].text(0,i*2.7+1.85,f'{s["saved_ms"]:+.0f} ms saved ({s["saved_percent"]:+.1f}%)',fontsize=9,color="#536279")
labels=[f'{"RAR" if rar else "MKV"} · {net.split("_")[0].replace("mbit"," Mbit/s")} · {"ON" if fast else "OFF"}' for net,rar in cases for fast in (False,True)]
for ax, title in zip(axes,["Total read sequence · medians and individual trials", "Stage durations · means (each bar adds to its measured total)"]):
    ax.set_yticks([i*2.7+int(fast) for i in range(4) for fast in (False,True)],labels)
    ax.invert_yaxis(); ax.grid(axis="x",alpha=.14); ax.set_axisbelow(True)
    ax.set_title(title,loc="left",fontsize=12,fontweight="bold",pad=12)
    ax.set_xlabel("Milliseconds")
    ax.set_xlim(0,ax.get_xlim()[1]*1.1)
axes[1].legend(loc="upper left",bbox_to_anchor=(0,-.15),ncol=2,frameon=False)
fig.subplots_adjust(left=.23,right=.95,top=.85,bottom=.115,hspace=.37)
fig.savefig(out/"startup-engine.png",dpi=180)
fig.savefig(out/"startup-engine.pdf")
print(json.dumps(summary,indent=2))
