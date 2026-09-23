"""Functional check for too-large parking and the size ceiling, with a real player: launches the
stress client so StressBot places hygrometers through the item and (shift-)right-clicks them,
and reads what its chat showed. Needs the stress server running with S4's sealed cave built.

    python tools/stress/parking_check.py            # everything but the restart
    python tools/stress/parking_check.py restart    # after a server restart (with greenhouseMaxHeight
                                                    # set to 4000 in the file while it was down)
    python tools/stress/parking_check.py report     # only the placement report, at spots the full
                                                    # run leaves free

Results: run-stress/results/F4-parking/checks.json.
"""
import json
import re
import subprocess
import sys
import time

from harness import Session, log, ROOT, RESULTS, SERVER, out_dir
from scenarios import force, status, wait_for, S4_X, Y
from client_session import wait_player, BOT, CLIENT

CHAT = CLIENT / "stress-chat.txt"
TOO_LARGE = "too large for a greenhouse"
HINT = "Shift-right-click the hygrometer to rescan"
RESCANNING = "[overlay] Rescanning the room"
SEALED = "Greenhouse sealed"  # the report's greenhouse line
MODE = sys.argv[1] if len(sys.argv) > 1 else "all"
OUT = out_dir("F4-parking") / ("report-checks.json" if MODE == "report" else "checks.json")
CHECKS = json.loads(OUT.read_text(encoding="utf-8")) if MODE == "restart" and OUT.exists() else []


def check(name, ok, detail=""):
    CHECKS.append({"check": name, "ok": bool(ok), "detail": detail})
    log(f"  [{'PASS' if ok else 'FAIL'}] {name} {detail}")
    OUT.write_text(json.dumps(CHECKS, indent=1), encoding="utf-8")


def chat():
    return CHAT.read_text(encoding="utf-8") if CHAT.exists() else ""


def state(s, label, i=0):
    entries = status(s, label)
    return entries[i].get("status") if len(entries) > i else None


def too_large_scans(s):
    return s.json("ccstress stats").get("counters", {}).get("scansFinished", {}).get("TOO_LARGE", 0)


def after(s, action, wait=4):
    """Runs an action and returns the chat lines it produced."""
    before = len(chat())
    action()
    time.sleep(wait)
    return chat()[before:]


def start_client(s):
    """Launches the stress client and waits for StressBot; returns the process, or None."""
    if CHAT.exists():
        CHAT.unlink()
    proc = subprocess.Popen(["cmd", "/c", str(ROOT / "gradlew.bat"), "runStressClient", "--console=plain"], cwd=str(ROOT),
                            stdout=open(RESULTS / "client-gradle.log", "w"), stderr=subprocess.STDOUT)
    log("client launched, waiting for StressBot")
    if not wait_player(s):
        log("! StressBot never joined; see run-stress/results/client-gradle.log")
        return None
    time.sleep(10)
    s.rules(3)
    s.c(f"gamemode creative {BOT}")
    s.c(f"tp {BOT} {S4_X + 10} 5 30")
    s.c(f"ccstress fly {BOT}")
    return proc


def stop_client(s, proc):
    s.c(f"kick {BOT} parking check over")
    time.sleep(5)
    proc.terminate()
    log(f"parking check: {sum(c['ok'] for c in CHECKS)}/{len(CHECKS)} passed")


def main(s):
    proc = start_client(s)
    if proc is None:
        return

    # A player hangs one in the sealed cave (576,000 cells).
    s.c(f"ccstress use cave4 {BOT} {S4_X - 1} 5 60 east")
    ok, t = wait_for(s, lambda: state(s, "cave4") == "TOO_LARGE", 30, 0.5)
    time.sleep(2)
    check("a hygrometer a player hangs in a cave over the cap reads too large", ok, f"after {t:.1f}s")
    check("that player is told in chat, with the rescan hint, and gets no greenhouse report",
          chat().count(TOO_LARGE) == 1 and HINT in chat() and SEALED not in chat(), chat().strip()[-200:])

    # Parked: block changes inside the cave start nothing.
    base = too_large_scans(s)
    for i in range(10):
        s.c(f"setblock {S4_X + 5} 5 60 {'stone' if i % 2 == 0 else 'air'}", quiet=True)
        time.sleep(2)
    time.sleep(20)
    check("40 s of block changes in the cave start no rescan", too_large_scans(s) == base and state(s, "cave4") == "TOO_LARGE",
          f"too-large scans {base} -> {too_large_scans(s)}")

    # A plain right-click reports, and still scans nothing.
    new = after(s, lambda: s.c(f"ccstress interact cave4 0 {BOT} false"), 8)
    check("a right-click shows the report (with the hint) and does not rescan", HINT in new and too_large_scans(s) == base,
          new.strip()[-160:])

    # A shift-right-click rescans, and the player hears it is still too large.
    before = len(chat())
    s.c(f"ccstress interact cave4 0 {BOT} true")
    ok, t = wait_for(s, lambda: too_large_scans(s) > base, 30, 0.5)
    time.sleep(2)
    new = chat()[before:]
    check("a shift-right-click says it is rescanning", RESCANNING in new, new.strip()[:120])
    check("and rescans once: still too large, told again", ok and new.count(TOO_LARGE) == 1
          and too_large_scans(s) == base + 1 and state(s, "cave4") == "TOO_LARGE", f"after {t:.1f}s")

    # Walled into a small room: nothing happens until a shift-right-click, which finds the greenhouse.
    s.c(f"fill {S4_X - 1} 3 57 {S4_X + 5} 8 63 glass hollow")
    time.sleep(5)
    check("walling it into a small room changes nothing by itself", state(s, "cave4") == "TOO_LARGE")
    before = len(chat())
    s.c(f"ccstress interact cave4 0 {BOT} true")
    ok, t = wait_for(s, lambda: state(s, "cave4") == "GREENHOUSE", 30, 0.5)
    time.sleep(2)
    check("a shift-right-click then finds the greenhouse, with no message and no report",
          ok and TOO_LARGE not in chat()[before:] and SEALED not in chat()[before:], f"after {t:.1f}s, {status(s, 'cave4')}")

    # Hung in an ordinary greenhouse, it joins it and its placer gets the report.
    new = after(s, lambda: s.c(f"ccstress use ctl {BOT} {S4_X + 10} {Y + 2} 153 east"), 6)
    check("a hygrometer hung in a normal greenhouse joins it and its placer gets the report",
          state(s, "ctl") == "GREENHOUSE" and SEALED in new and TOO_LARGE not in new, json.dumps(status(s, "ctl")))

    # The ceiling: radius 64 allows 565,794 cells on paper, but greenhouses stop at 262,144.
    s.config("greenhouseMaxRadius", 64)
    s.c(f"ccstress build greenhouse ceilA {S4_X - 5} {Y} 60 65 65 47 wheat 0")
    s.c(f"ccstress build greenhouse ceilB {S4_X + 62} {Y} 95 73 73 52 wheat 0")
    ok_a, _ = wait_for(s, lambda: state(s, "ceilA") == "GREENHOUSE", 90, 0.5)
    ok_b, _ = wait_for(s, lambda: state(s, "ceilB") == "TOO_LARGE", 90, 0.5)
    size_a = (status(s, "ceilA") or [{}])[0].get("size")
    check("radius 64: a ~196,000-cell room is a greenhouse", ok_a, f"size {size_a}")
    check("but a ~274,000-cell room is too large (ceiling 262,144)", ok_b, json.dumps(status(s, "ceilB")))
    logfile = (SERVER / "logs" / "latest.log").read_text(encoding="utf-8", errors="replace")
    check("the config above the ceiling is logged once", logfile.count("greenhouses are capped at 262144") == 1)
    s.config("greenhouseMaxRadius", 32)
    s.c(f"ccstress interact ceilA 0 {BOT} true")
    ok, _ = wait_for(s, lambda: state(s, "ceilA") == "TOO_LARGE", 60, 0.5)
    check("back at radius 32, a shift-right-click parks the ~196,000-cell room as too large", ok)

    stop_client(s, proc)


def report(s):
    """The report a player gets on hanging a hygrometer, at spots the full run leaves free."""
    proc = start_client(s)
    if proc is None:
        return
    # A sealed glass box with no hygrometer: hanging one makes the greenhouse, and the report arrives.
    s.c(f"fill {S4_X + 25} {Y + 1} 165 {S4_X + 31} {Y + 6} 171 glass hollow")
    new = after(s, lambda: s.c(f"ccstress use fresh {BOT} {S4_X + 25} {Y + 3} 168 east"), 6)
    check("a hygrometer hung in a sealed room makes a greenhouse and its placer gets the report at once",
          state(s, "fresh") == "GREENHOUSE" and SEALED in new and "Hygrometer" in new, new.strip()[-240:])
    # Hung in an existing greenhouse it joins without scanning: the report still comes.
    new = after(s, lambda: s.c(f"ccstress use ctl2 {BOT} {S4_X + 10} {Y + 2} 157 east"), 6)
    check("one hung in an existing greenhouse joins it and its placer gets the report",
          state(s, "ctl2") == "GREENHOUSE" and SEALED in new, json.dumps(status(s, "ctl2")))
    # Hung in the cave: the too-large message, no report.
    new = after(s, lambda: s.c(f"ccstress use cave5 {BOT} {S4_X - 1} 5 90 east"), 10)
    check("one hung in the cave gets the too-large message and no report",
          state(s, "cave5") == "TOO_LARGE" and TOO_LARGE in new and SEALED not in new, new.strip()[-160:])
    # Shift-right-click is a rescan, not a placement: no report when it is still a greenhouse.
    new = after(s, lambda: s.c(f"ccstress interact fresh 0 {BOT} true"), 6)
    check("a shift-right-click on a greenhouse rescans without a report", RESCANNING in new and SEALED not in new,
          new.strip()[-160:])
    new = after(s, lambda: s.c(f"ccstress interact fresh 0 {BOT} false"), 4)
    check("a plain right-click still shows the report", SEALED in new)
    stop_client(s, proc)


def after_restart(s):
    """Run right after a restart, with greenhouseMaxHeight = 4000 put in the file while the server was down."""
    time.sleep(40)
    n = too_large_scans(s)
    check("after a restart, parked hygrometers stay parked", n == 0 and state(s, "ceilA") == "TOO_LARGE"
          and state(s, "ceilB") == "TOO_LARGE", f"too-large scans in 40 s: {n}")
    toml = (SERVER / "config" / "crop_climates-server.toml").read_text(encoding="utf-8")
    m = re.search(r"greenhouseMaxHeight\s*=\s*(\d+)", toml)
    check("greenhouseMaxHeight = 4000 in the file is corrected to 383", bool(m) and m.group(1) == "383",
          m.group(0) if m else "not found")
    s.config("greenhouseMaxHeight", 34)  # back to the default for later runs
    log(f"parking check: {sum(c['ok'] for c in CHECKS)}/{len(CHECKS)} passed")


if __name__ == "__main__":
    session = Session()
    force(session, S4_X - 8, -8, S4_X + 136, 200)
    if MODE == "restart":
        after_restart(session)
    elif MODE == "report":
        report(session)
    else:
        main(session)
