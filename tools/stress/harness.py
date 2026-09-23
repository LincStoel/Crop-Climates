"""Scenario plumbing shared by the stress scripts: RCON session, timed windows,
local spark profiles and their summaries. See README.md for the workflow.

Every window writes into run-stress/results/<label>/:
  mspt.json        per-tick stats + crop_climates counters (from /ccstress mspt)
  ticks.csv        per-tick wall time
  profile.sparkprofile + profile.txt + profile.json   (spark, decoded locally)
"""
import json
import pathlib
import shutil
import subprocess
import sys
import time

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from rcon import Rcon, wait_for_server  # noqa: E402

ROOT = pathlib.Path(__file__).resolve().parents[2]
SERVER = ROOT / "run-stress" / "server"
RESULTS = ROOT / "run-stress" / "results"
SPARK_DIR = SERVER / "config" / "spark"
SPARK_JAR = next((ROOT / "run" / "mods").glob("spark-*.jar"))
JAVA = r"C:\Program Files\Java\jdk-21.0.11\bin\java.exe"
SUMMARY = ROOT / "tools" / "stress" / "SparkSummary.java"


def log(msg):
    line = time.strftime("%H:%M:%S ") + msg
    print(line, flush=True)
    RESULTS.mkdir(parents=True, exist_ok=True)
    with open(RESULTS / "session.log", "a", encoding="utf-8") as f:
        f.write(line + "\n")


class Session:
    def __init__(self):
        self.r = wait_for_server()

    def c(self, command, quiet=False):
        out = self.r.cmd(command)
        if not quiet:
            log(f"> {command}\n  {out.strip()[:600]}")
        return out

    def json(self, command):
        out = self.r.cmd(command).strip()
        try:
            return json.loads(out)
        except json.JSONDecodeError:
            log(f"! non-JSON reply to {command}: {out[:300]}")
            return {"raw": out}

    # ------------------------------------------------------------------ world

    def rules(self, random_tick_speed=3):
        for cmd in ["gamerule doDaylightCycle false", "time set 6000", "gamerule doWeatherCycle false",
                    "weather clear", "gamerule doMobSpawning false", "gamerule doFireTick false",
                    "gamerule commandModificationBlockLimit 2147483647", "gamerule spawnChunkRadius 0",
                    f"gamerule randomTickSpeed {random_tick_speed}"]:
            self.c(cmd, quiet=True)

    def config(self, key, value):
        return self.c(f"ccstress config {key} {value}")

    # --------------------------------------------------------------- windows

    def window(self, label, seconds, profile=False, interval=4):
        """One timed window: per-tick histogram and counters; optionally a spark profile."""
        out = RESULTS / label
        out.mkdir(parents=True, exist_ok=True)
        before = set(SPARK_DIR.glob("*.sparkprofile")) if SPARK_DIR.exists() else set()
        if profile:
            self.c(f"spark profiler start --interval {interval}", quiet=True)
        self.c(f"ccstress mspt start {label}", quiet=True)
        time.sleep(seconds)
        stats = self.json("ccstress mspt stop")
        if profile:
            self.c("spark profiler stop --save-to-file", quiet=True)
        (out / "mspt.json").write_text(json.dumps(stats, indent=1), encoding="utf-8")
        csv = SERVER / "stress-results" / f"{label}-ticks.csv"
        if csv.exists():
            shutil.copy2(csv, out / "ticks.csv")
        if profile:
            prof = self._await_profile(before)
            if prof:
                shutil.copy2(prof, out / "profile.sparkprofile")
                summary = self.summarise(out / "profile.sparkprofile", out / "profile.json")
                (out / "profile.txt").write_text(summary, encoding="utf-8")
        log(f"window {label}: " + brief(stats))
        return stats

    def _await_profile(self, before, timeout=120):
        deadline = time.time() + timeout
        while time.time() < deadline:
            now = set(SPARK_DIR.glob("*.sparkprofile"))
            new = sorted(now - before, key=lambda p: p.stat().st_mtime)
            if new:
                time.sleep(1.0)  # let spark finish writing
                return new[-1]
            time.sleep(1.0)
        log("! spark profile did not appear")
        return None

    @staticmethod
    def summarise(profile, json_out):
        res = subprocess.run([JAVA, "-cp", str(SPARK_JAR), str(SUMMARY), str(profile), "--json", str(json_out)],
                             capture_output=True, text=True, encoding="utf-8")
        return res.stdout + res.stderr


def brief(stats):
    if "meanMs" not in stats:
        return json.dumps(stats)[:300]
    c = stats.get("counters", {})
    return (f"ticks={stats['ticks']} mean={stats['meanMs']:.2f} p50={stats['p50Ms']:.2f} p95={stats['p95Ms']:.2f} "
            f"p99={stats['p99Ms']:.2f} max={stats['maxMs']:.2f} >50ms={stats['over50ms']} | "
            f"pre={c.get('preCalls')} vetoes={c.get('vetoes')} tempFresh={c.get('tempFreshReads')} "
            f"tempWipes={c.get('tempWipes')} tempUnavail={c.get('tempUnavailable')} cells={c.get('cellsScanned')} "
            f"scanTicks={c.get('ticksScanning')} regTick={c.get('registryTickMs')}ms "
            f"scans={c.get('scansFinished')} particles={c.get('particlePackets')}")
