"""Unattended client session: launches the stress client (auto-joins as StressBot),
flies it through the scenarios, logs FPS/particles per station, takes
screenshots and captures the Alt tooltip. Needs the stress server running with
the scenarios already built (s1, s2, s3).

    python tools/stress/client_session.py            # the whole tour
    python tools/stress/client_session.py tooltips   # only the Alt tooltips on farmland, clear vs rain

Output: run-stress/client/stress-client.csv (FPS, particles, entities per second,
with the station name as marker), run-stress/client/screenshots/*.png,
run-stress/client/stress-tooltips.txt, run-stress/results/client-*/mspt.json.
"""
import pathlib
import subprocess
import sys
import time

from harness import Session, log, RESULTS, ROOT

CLIENT = ROOT / "run-stress" / "client"
BOT = "StressBot"


def wait_player(s, timeout=600):
    t0 = time.time()
    while time.time() - t0 < timeout:
        if BOT in s.c("list", quiet=True):
            return True
        time.sleep(3)
    return False


def station(s, name, x, y, z, yaw, pitch, settle=12, measure=20, shot=True):
    s.c(f"tp {BOT} {x} {y} {z} {yaw} {pitch}", quiet=True)
    # The client drops flight whenever it touches ground; turned on in mid-air, it holds.
    s.c(f"ccstress fly {BOT}", quiet=True)
    s.c(f"ccstress instruct {BOT} log {name}", quiet=True)
    time.sleep(settle)
    stats = s.window(f"client-{name}", measure)
    if shot:
        s.c(f"ccstress instruct {BOT} shot {name}", quiet=True)
        time.sleep(2)
    return stats


def tour(s):
    # A dry greenhouse for dust (desert, few water cells) and outdoor hygrometers for the dial.
    s.c("ccstress ticket add 5592 -8 5700 60")
    s.c("fillbiome 5592 -64 -8 5700 319 60 minecraft:desert")
    s.c("ccstress build greenhouse dust 5600 64 0 20 20 5 wheat+carrots 0")
    s.c("setblock 5640 65 10 stone")
    s.c("ccstress hang dial-desert 5641 65 10 east")
    s.c("fillbiome 5650 -64 -8 5700 319 60 minecraft:plains")
    s.c("setblock 5660 65 10 stone")
    s.c("ccstress hang dial-plains 5661 65 10 east")
    time.sleep(5)

    # S2: the max greenhouse (jungle, humid enough to drip), inside and at the hygrometer.
    station(s, "S2-inside", 5033, 80, 20, -150, 20)
    station(s, "S2-hygrometer", 5003.5, 80, 33.5, 90, 0, settle=6, measure=10)
    # S3: the 2025-greenhouse grid from above, then from inside one room.
    station(s, "S3-overview", 7800, 110, -40, 0, 45)
    station(s, "S3-inside", 7604.5, 65, 3.5, 90, 5, settle=6, measure=10)
    # Dust greenhouse and the outdoor dials: dry (desert) and neutral (plains).
    station(s, "dust-inside", 5610, 67, 10, 0, 25)
    station(s, "dial-desert", 5643.5, 65, 10.5, 90, 25, settle=6, measure=10)
    station(s, "dial-plains", 5663.5, 65, 10.5, 90, 25, settle=6, measure=10)
    # Jade: crosshair on a crop, then on a hygrometer.
    station(s, "jade-crop", 5610.5, 66.6, 6.5, 0, 60, settle=6, measure=6)
    station(s, "jade-hygrometer", 5603.5, 66, 11.5, 90, 25, settle=6, measure=6)
    # Wilting: the hostile desert field of S1 at high random tick speed.
    s.c("ccstress ticket add 2992 290 3232 536")
    s.c("gamerule randomTickSpeed 300")
    station(s, "S1-wilt", 3100, 68, 400, 0, 50)
    s.c("gamerule randomTickSpeed 3")
    s.c("ccstress ticket remove 2992 290 3232 536")
    s.c("ccstress ticket remove 5592 -8 5700 60")


def tooltips(s):
    """Standing on farmland in the plains field (S1a), clear then raining."""
    s.c("ccstress ticket add 2992 -8 3232 240")
    s.c(f"tp {BOT} 3002.5 64.94 2.5 0 30")
    time.sleep(3)
    for weather in ("clear", "rain"):
        s.c(f"weather {weather}")
        time.sleep(3)  # rain fades in over a second
        for item in ("minecraft:wheat_seeds", "minecraft:bamboo"):
            s.c(f"ccstress instruct {BOT} log tooltip-{weather}")
            s.c(f"ccstress instruct {BOT} tooltip {item}")
            time.sleep(2)
            s.c(f"ccstress instruct {BOT} tooltip {item}")  # the verdict line answers the first request
            time.sleep(1)
    s.c("weather clear")
    s.c("ccstress ticket remove 2992 -8 3232 240")


def main():
    only = sys.argv[1] if len(sys.argv) > 1 else "all"
    s = Session()
    proc = subprocess.Popen(["cmd", "/c", str(ROOT / "gradlew.bat"), "runStressClient", "--console=plain"], cwd=str(ROOT),
                            stdout=open(RESULTS / "client-gradle.log", "w"), stderr=subprocess.STDOUT)
    log("client launched, waiting for StressBot")
    if not wait_player(s):
        log("! StressBot never joined; see run-stress/results/client-gradle.log")
        return
    time.sleep(10)
    s.rules(3)
    s.c(f"gamemode creative {BOT}")
    s.c(f"ccstress fly {BOT}")
    s.c(f"effect give {BOT} minecraft:night_vision infinite 0 true")
    if only == "all":
        tour(s)
    tooltips(s)
    s.c(f"kick {BOT} stress session over")
    time.sleep(5)
    proc.terminate()
    log("client session done")


if __name__ == "__main__":
    main()
