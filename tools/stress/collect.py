"""Collects every results window into one JSON for the report:

    python tools/stress/collect.py [results-dir] [out.json]

Per window: MSPT percentiles, the crop_climates counters, and - for profiled
windows - the share of tick time spent in crop_climates code and its biggest
entry points (decoded locally from the saved spark profile).
"""
import json
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]


def collect(results):
    out = {}
    for d in sorted(p for p in results.iterdir() if p.is_dir()):
        entry = {}
        m = d / "mspt.json"
        if m.exists():
            stats = json.loads(m.read_text(encoding="utf-8"))
            entry["mspt"] = {k: stats.get(k) for k in ("ticks", "wallSeconds", "tps", "meanMs", "p50Ms", "p95Ms", "p99Ms",
                                                        "p999Ms", "maxMs", "over25ms", "over50ms",
                                                        "allLeakedSeconds", "allResealedSeconds", "datBytes")}
            entry["counters"] = stats.get("counters", {})
            entry["state"] = stats.get("state", {})
        p = d / "profile.json"
        if p.exists():
            prof = json.loads(p.read_text(encoding="utf-8"))
            tick = prof.get("tickMs") or prof.get("totalMs")
            ours = prof.get("ours", {})
            entry["profile"] = {
                "tickMs": tick,
                "oursMs": prof.get("oursMs"),
                "oursPct": 100 * prof.get("oursMs", 0) / tick if tick else None,
                "topOurs": sorted(((k, v) for k, v in ours.items()), key=lambda kv: -kv[1])[:8],
                "focus": prof.get("focus", {}),
            }
        for extra in ("latency.json", "checks.json", "summary.json"):
            f = d / extra
            if f.exists():
                entry[extra[:-5]] = json.loads(f.read_text(encoding="utf-8"))
        if entry:
            out[d.name] = entry
    for f in results.glob("*.json"):
        out[f.stem] = json.loads(f.read_text(encoding="utf-8"))
    return out


if __name__ == "__main__":
    results = pathlib.Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / "run-stress" / "results"
    target = pathlib.Path(sys.argv[2]) if len(sys.argv) > 2 else results / "collected.json"
    data = collect(results)
    target.write_text(json.dumps(data, indent=1), encoding="utf-8")
    print(f"{len(data)} entries -> {target}")
