"""Plot recorded startup trials; never fill missing measurements with estimates.

python plot-startup.py --native-log LOG --android JSONL --bootstrap JSONL --out DIR
Requires matplotlib. Benchmarks use a generated test-pattern MKV, not user media.
"""
import argparse
import csv
import json
from pathlib import Path
from statistics import mean, median

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.patches import Patch

parser = argparse.ArgumentParser()
parser.add_argument("--native-log", required=True)
parser.add_argument("--android", required=True)
parser.add_argument("--bootstrap", required=True)
parser.add_argument("--out", required=True)
args = parser.parse_args()
out = Path(args.out)
out.mkdir(parents=True, exist_ok=True)
native = [json.loads(line.split("STARTUP_RESULT ", 1)[1]) for line in Path(args.native_log).read_text().splitlines() if "STARTUP_RESULT " in line]
android = [json.loads(line) for line in Path(args.android).read_text().splitlines() if line.strip()]
bootstrap = [json.loads(line) for line in Path(args.bootstrap).read_text().splitlines() if line.strip()]
assert native and android and bootstrap, "All three measured datasets are required"
cases = [(net, archive) for net in ("100mbit_40ms", "400mbit_80ms") for archive in (False, True)]
labels = [f'{"Stored RAR" if ar else "Direct MKV"}\n{net.replace("mbit_", " Mbit/s · ").replace("ms", " ms RTT")}' for net, ar in cases]

def subset(rows, key, fast):
    return [r for r in rows if (r["network"], r["archive"]) == key and r.get("fast", r.get("fastMkvStartup")) == fast]

def save_csv(name, rows):
    with (out / name).open("w", newline="", encoding="utf-8") as file:
        writer = csv.DictWriter(file, fieldnames=list(dict.fromkeys(k for r in rows for k in r)))
        writer.writeheader()
        writer.writerows(rows)

save_csv("native-startup.csv", [{k: v for k, v in r.items() if k != "diagnostics"} | {
    "archive_discovery_ms": r["diagnostics"].get("archiveDiscoveryMs"),
    **r["diagnostics"]["store"]
} for r in native])
save_csv("exoplayer-startup.csv", [{"network": r["network"], "archive": r["archive"], "trial": r["trial"], "fast": r["fastMkvStartup"], **r["marksMs"]} for r in android])
save_csv("engine-bootstrap.csv", bootstrap)
for name, rows in (("native-startup.jsonl", native), ("exoplayer-startup.jsonl", android)):
    (out / name).write_text("\n".join(json.dumps(row) for row in rows) + "\n")

plt.rcParams.update({"font.family": "DejaVu Sans", "font.size": 10, "axes.spines.top": False,
    "axes.spines.right": False, "axes.spines.left": False, "axes.spines.bottom": False,
    "axes.titleweight": "bold", "figure.facecolor": "#f7f9fc", "axes.facecolor": "#f7f9fc",
    "text.color": "#17263c", "axes.labelcolor": "#17263c", "xtick.color": "#536279", "ytick.color": "#17263c"})
colors = ["#8696b0", "#008a78"]
summary = {"scope": "Controlled NNTP links; fresh sessions; warm Go engine for playback trials; Android TV emulator; no physical-TV claim", "cases": []}
fig, (ax, bx) = plt.subplots(2, 1, figsize=(12, 10), gridspec_kw={"height_ratios": [3.5, 1.3]})
fig.suptitle("Fast MKV Startup · measured time to first frame", x=.06, ha="left", fontsize=20, fontweight="bold")
fig.text(.06, .935, "Android TV emulator · real ExoPlayer + packaged engine · alternating ON/OFF · fresh cache each trial", color="#536279")
for i, key in enumerate(cases):
    sample_sizes = []
    med = []
    for fast in (False, True):
        rows = subset(android, key, fast)
        values = [r["marksMs"]["first_frame"] for r in rows]
        assert values, (key, fast)
        sample_sizes.append(len(values))
        m = median(values); med.append(m)
        y = i * 2.7 + int(fast) * .85
        ax.barh(y, m, height=.66, color=colors[int(fast)])
        ax.scatter(values, [y] * len(values), s=16, color="#17263c", alpha=.55, zorder=4)
        ax.text(m + 25, y, f'{m:,.0f} ms  {"ON" if fast else "OFF"}', va="center", fontsize=10)
    delta = med[0] - med[1]
    summary["cases"].append({"network": key[0], "archive": key[1], "trials_off": sample_sizes[0], "trials_on": sample_sizes[1],
        "median_first_frame_off_ms": med[0], "median_first_frame_on_ms": med[1], "saved_ms": delta,
        "saved_percent": 100 * delta / med[0]})
    ax.text(0, i*2.7+1.55, f'{delta:+,.0f} ms saved ({100*delta/med[0]:+.1f}%) · {sample_sizes[0]} + {sample_sizes[1]} trials', fontsize=9, color="#536279")
ax.set_yticks([i*2.7+.425 for i in range(4)], labels)
ax.invert_yaxis(); ax.set_xlabel("From Usenet resolution start to first rendered frame (milliseconds)")
ax.set_xlim(0, max(r["marksMs"]["first_frame"] for r in android)*1.25)
ax.grid(axis="x", alpha=.15); ax.set_axisbelow(True)
boot_medians = [median(r[k] for r in bootstrap) for k in ("cold_engine_ms", "warm_engine_ms")]
bx.barh([1,0], boot_medians, height=.55, color=colors)
for y, value in zip([1,0], boot_medians): bx.text(value+3,y,f"{value:,.0f} ms",va="center")
bx.set_yticks([1,0], ["Cold Go process + Android trust roots", "Already prewarmed engine"])
bx.set_title("Engine prewarming is a separate startup stage", loc="left", fontsize=12)
bx.set_xlabel("Milliseconds · six process-bootstrap trials; no provider connections")
bx.set_xlim(0, max(boot_medians)*1.35+10); bx.grid(axis="x", alpha=.15)
fig.subplots_adjust(left=.25,right=.95,top=.89,bottom=.09,hspace=.6)
fig.savefig(out/"startup-first-frame.png",dpi=180)
fig.savefig(out/"startup-first-frame.pdf")
plt.close(fig)

# Stack arithmetic means so each bar adds exactly to its mean measured total.
stages = ["Resolve session", "Player handoff", "Prepare → tracks", "Tracks → decoder ready", "Decoder → first frame"]
palette = ["#577bca", "#b9c6d9", "#d69425", "#8254ad", "#008a78"]
def stages_for(r):
    m = r["marksMs"]
    points = [0,m["resolved"],m["prepare"],m.get("tracks_known"),m.get("decoder_ready"),m["first_frame"]]
    assert all(p is not None for p in points), "A player stage was not observed"
    assert all(a <= b for a,b in zip(points,points[1:])), ("Overlapping player marks require a separate timeline",points)
    return [b-a for a,b in zip(points,points[1:])]

fig, axes = plt.subplots(2,1,figsize=(14,12))
fig.suptitle("Where startup time goes", x=.06, ha="left", fontsize=22, fontweight="bold")
for chart, rows, title, names, get_values in [
    (axes[0], android, "Actual ExoPlayer first frame · mean stage durations", stages, stages_for),
    (axes[1], native, "Engine read sequence · head → Cues → return + 2 MiB buffer (no decoder)",
        ["Open session", "Read 64 KiB head", "Read Cues / discover RAR", "Return + buffer 2 MiB"],
        lambda r: [r[k] for k in ("session_ms","head_ms","cues_ms","buffer_ms")])]:
    yticks=[]; ylabels=[]
    for i,key in enumerate(cases):
        for fast in (False,True):
            values=[get_values(r) for r in subset(rows,key,fast)]
            means=[mean(col) for col in zip(*values)]
            y=i*2.7+int(fast); left=0
            for j,d in enumerate(means):
                chart.barh(y,d,left=left,height=.74,color=palette[j])
                if d>70: chart.text(left+d/2,y,f"{d:.0f}",ha="center",va="center",fontsize=8,color="white")
                left += d
            chart.text(left+15,y,f"{left:,.0f} ms",va="center",fontsize=9)
            yticks.append(y); ylabels.append(f'{"RAR" if key[1] else "MKV"} · {key[0].split("_")[0]} · {"ON" if fast else "OFF"}')
    chart.set_yticks(yticks,ylabels); chart.invert_yaxis(); chart.set_title(title,loc="left",fontsize=12,pad=12)
    chart.legend(handles=[Patch(facecolor=palette[j],label=name) for j,name in enumerate(names)],loc="upper left",bbox_to_anchor=(0,-.16),ncol=3,frameon=False,fontsize=9)
    chart.set_xlabel("Milliseconds"); chart.grid(axis="x",alpha=.15); chart.set_axisbelow(True)
    chart.set_xlim(0,chart.get_xlim()[1]*1.09)
fig.subplots_adjust(left=.19,right=.95,top=.91,bottom=.11,hspace=.52)
fig.savefig(out/"startup-breakdown.png",dpi=180); fig.savefig(out/"startup-breakdown.pdf"); plt.close(fig)
summary["bootstrap_median_ms"] = {"cold":boot_medians[0],"prewarmed":boot_medians[1]}
summary["native_cases"] = [{"network":key[0],"archive":key[1],"fast":fast,
    **{k:median(r[k] for r in subset(native,key,fast)) for k in ("session_ms","head_ms","cues_ms","buffer_ms","total_ms")},
    "archive_discovery_ms":median(r["diagnostics"].get("archiveDiscoveryMs",0) for r in subset(native,key,fast))
} for key in cases for fast in (False,True)]
(out/"summary.json").write_text(json.dumps(summary,indent=2)+"\n")
print(json.dumps(summary,indent=2))
