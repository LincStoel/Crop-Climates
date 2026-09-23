"""Stress and functional scenarios for crop_climates. Usage:

    python tools/stress/scenarios.py <scenario> [<scenario> ...]

Needs the stress server running (gradlew runStressServer). World layout
(flat world, soil layer y=64), so scenarios never overlap:

    S0 idle        x 2000..2160,  z 0..160
    F  growth      x 20000.., z 0..        (desert and jungle test fields)
    S1 field       x 3000..3460,  z 0..230
    S2 greenhouse  x 5000..5600,  z 0..70
    S3 grid        x 7000..7405,  z 0..405
    S4 pathology   x 9000..,      nether at x 0.., z 0
    S5 churn       x 11000..11100
"""
import json
import sys
import time

from harness import Session, log, RESULTS, out_dir, TAG

Y = 64
PLANTS_VANILLA = "wheat+carrots+potatoes+beetroots+melon_stem+sweet_berry+sugar_cane+cactus+pitcher+oak_sapling+bamboo"


def force(s, x1, z1, x2, z2):
    return s.c(f"ccstress ticket add {x1} {z1} {x2} {z2}")


def release(s, x1, z1, x2, z2):
    return s.c(f"ccstress ticket remove {x1} {z1} {x2} {z2}")


def biome(s, x1, z1, x2, z2, b):
    return s.c(f"fillbiome {x1} -64 {z1} {x2} 319 {z2} {b}")


# ---------------------------------------------------------------- S0 baseline

def s0(s):
    """Idle baseline: 100 forced empty chunks at randomTickSpeed 3 and 300."""
    s.rules(3)
    force(s, 2000, 0, 2159, 159)
    time.sleep(10)
    for rts in (3, 300):
        s.c(f"gamerule randomTickSpeed {rts}", quiet=True)
        time.sleep(5)
        s.window(f"S0-idle-rts{rts}", 60)
        s.window(f"S0-idle-rts{rts}-prof", 60, profile=True)
    s.c("gamerule randomTickSpeed 3", quiet=True)
    release(s, 2000, 0, 2159, 159)


# ------------------------------------------------------- F1 growth-rate check

F_X = 20000
F_DEPTH = 60
F_FIELDS = [
    # biome, z, types, width
    ("desert", 0, "wheat+carrots+beetroots+sugar_cane+sweet_berry+pitcher+melon_stem+bamboo+cactus+oak_sapling", 220),
    ("snowy_plains", 100, "wheat+carrots+beetroots+potatoes+sweet_berry+pitcher+torchflower+melon_stem", 176),
    ("flower_forest", 200, "melon_stem+bamboo+oak_sapling+wheat+sugar_cane", 220),
    ("birch_forest", 300, "sugar_cane", 60),
]
DEFAULTS = {"growthFloor": 0.005, "growthMax": 1.25, "regressionEnabled": "true", "tempReadsPerTick": 12}


def f1(s, seconds=120, rts=12):
    """Growth-rate check: the same fields grown with every multiplier forced to 1.0 (control =
    vanilla speed) and with the real defaults (treatment); observed ratio vs predicted multiplier."""
    s.rules(0)
    force(s, F_X, 0, F_X + 230, 360)
    for b, z, types, w in F_FIELDS:
        biome(s, F_X, z, F_X + w - 1, z + F_DEPTH - 1, "minecraft:" + b)
    s.config("regressionEnabled", "false")
    reports = {}
    for run, (floor, mx) in [("control", (1.0, 1.0)), ("treatment", (0.005, 1.25))]:
        s.config("growthFloor", floor)
        s.config("growthMax", mx)
        s.c("gamerule randomTickSpeed 0", quiet=True)
        for b, z, types, w in F_FIELDS:
            s.c(f"ccstress build field F-{b} {F_X} {Y} {z} {w} {F_DEPTH} {types} 0 40")
        s.config("tempReadsPerTick", 100000)
        for b, z, types, w in F_FIELDS:
            s.c(f"ccstress growth track {run}-{b} {F_X} {Y} {z} {F_X + w - 1} {Y + 3} {z + F_DEPTH - 1}")
        s.config("tempReadsPerTick", 12)
        s.c(f"gamerule randomTickSpeed {rts}", quiet=True)
        s.window(f"F1-{run}", seconds)
        s.c("gamerule randomTickSpeed 0", quiet=True)
        for b, *_ in F_FIELDS:
            reports[(run, b)] = s.json(f"ccstress growth report {run}-{b}")
    for k, v in DEFAULTS.items():
        s.config(k, v)
    s.c("gamerule randomTickSpeed 3", quiet=True)
    rows = []
    for b, *_ in F_FIELDS:
        ctl = reports[("control", b)]["types"]
        trt = reports[("treatment", b)]["types"]
        for t in sorted(trt):
            c, x = ctl.get(t), trt[t]
            if not c or not c["units"]:
                continue
            ratio = x["units"] / c["units"]
            fer = (x["firstEventRate"] / c["firstEventRate"]) if c["firstEventRate"] else float("nan")
            rows.append({"biome": b, "type": t, "plants": x["plants"], "controlUnits": c["units"],
                         "treatmentUnits": x["units"], "ratio": ratio, "firstEventRatio": fer,
                         "predicted": x.get("predictedMean"), "predictedUnavailable": x.get("predictedUnavailable")})
    out = out_dir("F1-growth")
    (out / "reports.json").write_text(json.dumps({f"{k[0]}-{k[1]}": v for k, v in reports.items()}, indent=1))
    (out / "summary.json").write_text(json.dumps(rows, indent=1))
    log("F1 growth-rate check (observed treatment/control vs predicted):")
    for r in rows:
        log(f"  {r['biome']:14} {r['type']:28} n={r['plants']:5} ctl={r['controlUnits']:8.0f} trt={r['treatmentUnits']:8.0f}"
            f" ratio={r['ratio']:.3f} first-event={r['firstEventRatio']:.3f} predicted={r['predicted']:.3f}")
    release(s, F_X, 0, F_X + 230, 360)


# ---------------------------------------------------- F2 greenhouse lifecycle

F2_X = 40000
CHECKS = []


def check(name, ok, detail=""):
    CHECKS.append({"check": name, "ok": bool(ok), "detail": detail})
    log(f"  [{'PASS' if ok else 'FAIL'}] {name} {detail}")


def status(s, label):
    out = s.json(f"ccstress status {label}")
    return out if isinstance(out, list) else []


def wait_for(s, predicate, timeout=60.0, poll=0.25):
    """Polls predicate() until true; returns (ok, seconds)."""
    t0 = time.time()
    while time.time() - t0 < timeout:
        if predicate():
            return True, time.time() - t0
        time.sleep(poll)
    return False, time.time() - t0


def all_status(s, label, want):
    st = status(s, label)
    return bool(st) and all(e["status"] == want for e in st)


def f2(s):
    s.rules(3)
    CHECKS.clear()
    force(s, F2_X - 16, -16, F2_X + 330, 120)
    for b in ("plains",):
        biome(s, F2_X - 16, -16, F2_X + 119, 120, f"minecraft:{b}")
    biome(s, F2_X + 120, -16, F2_X + 330, 120, "minecraft:desert")
    s.c("ccstress hygrometers kill", quiet=True)

    # a) seal, leak, reseal
    s.c(f"ccstress build greenhouse f2a {F2_X} {Y} 0 9 9 5 wheat+carrots 0")
    ok, t = wait_for(s, lambda: all_status(s, "f2a", "GREENHOUSE"), 10)
    check("new greenhouse is recognised", ok, f"after {t:.1f}s")
    s.c("ccstress latency f2a 0")
    ok, t = wait_for(s, lambda: s.c("ccstress latency result", quiet=True).strip() not in ("running", "none"), 120)
    lat = json.loads(s.c("ccstress latency result", quiet=True))
    check("roof hole reported as a leak", lat.get("leakTicks", -1) > 0, json.dumps(lat))
    check("leak reported within 60 ticks", 0 < lat.get("leakTicks", 99999) <= 60, f"leakTicks={lat.get('leakTicks')}")
    check("reseal reported within 60 ticks", 0 < lat.get("resealTicks", 99999) <= 60, f"resealTicks={lat.get('resealTicks')}")

    # b) merge through a shared wall, then split again
    bx = F2_X + 30
    s.c(f"ccstress build grid f2b {bx} {Y} 0 2 1 7 7 4 -1 wheat 0")
    ok, _ = wait_for(s, lambda: all_status(s, "f2b", "GREENHOUSE"), 10)
    st = status(s, "f2b")
    check("two rooms sharing a wall are separate", ok and st[0]["room"] != st[1]["room"], json.dumps(st))
    wall_x = bx + 8  # second room's west wall is the first room's east wall
    # z=2, not z=4: the second hygrometer hangs on (wall_x, Y+2, 4) and would drop with it.
    s.c(f"setblock {wall_x} {Y + 2} 2 air")
    ok, t = wait_for(s, lambda: (lambda st: len(st) == 2 and st[0].get("room") == st[1].get("room")
                                 and st[0]["status"] == "GREENHOUSE")(status(s, "f2b")), 20)
    check("removing the shared wall block merges the rooms", ok, f"after {t:.1f}s {json.dumps(status(s, 'f2b'))}")
    s.c(f"setblock {wall_x} {Y + 2} 2 glass")
    ok, t = wait_for(s, lambda: (lambda st: len(st) == 2 and st[0].get("room") != st[1].get("room")
                                 and all(e["status"] == "GREENHOUSE" for e in st))(status(s, "f2b")), 30)
    check("putting it back splits them again", ok, f"after {t:.1f}s {json.dumps(status(s, 'f2b'))}")

    # c) two hygrometers in one room; remove the anchor
    cx = F2_X + 60
    s.c(f"ccstress build greenhouse f2c {cx} {Y} 0 9 9 5 wheat 0")
    s.c(f"ccstress hang f2c {cx + 9} {Y + 2} 5 west")
    ok, _ = wait_for(s, lambda: (lambda st: len(st) == 2 and all(e["status"] == "GREENHOUSE" for e in st)
                                 and st[0]["room"] == st[1]["room"])(status(s, "f2c")), 15)
    check("second hygrometer joins the same room", ok, json.dumps(status(s, "f2c")))
    s.c(f"kill @e[type=crop_climates:hygrometer,x={cx + 1},y={Y + 2},z=5,distance=..1.5]")
    ok, t = wait_for(s, lambda: (lambda st: st and st[1]["status"] == "GREENHOUSE" and st[1].get("members") == 1)(
        status(s, "f2c")), 30)
    check("room survives losing its anchor", ok, f"after {t:.1f}s {json.dumps(status(s, 'f2c'))}")

    # d) the wall behind a hygrometer is broken
    dx = F2_X + 90
    s.c(f"ccstress build greenhouse f2d {dx} {Y} 0 7 7 4 wheat 0")
    wait_for(s, lambda: all_status(s, "f2d", "GREENHOUSE"), 10)
    before = s.json("ccstress stats")["minecraft:overworld"]["hygrometers"]
    s.c(f"setblock {dx} {Y + 2} 4 air destroy")
    time.sleep(0.5)
    after = s.json("ccstress stats")["minecraft:overworld"]["hygrometers"]
    items = s.c(f"execute if entity @e[type=item,x={dx + 1},y={Y + 2},z=4,distance=..3,nbt={{Item:{{id:\"crop_climates:hygrometer\"}}}}]",
                quiet=True)
    check("hygrometer drops at once when its wall breaks", after == before - 1 and "passed" in items.lower(),
          f"registry {before}->{after}; item test: {items.strip()}")

    # e) statuses: too small, too large, outdoor
    s.c(f"ccstress build greenhouse f2e1 {F2_X} {Y} 40 2 2 2 wheat 0")
    ok, _ = wait_for(s, lambda: all_status(s, "f2e1", "TOO_SMALL"), 10)
    check("a 2x2x2 room reads too small", ok, json.dumps(status(s, "f2e1")))
    s.config("greenhouseMaxRadius", 2)
    s.config("greenhouseMaxHeight", 2)
    s.c(f"ccstress build greenhouse f2e2 {F2_X + 20} {Y} 40 9 9 5 wheat 0")
    ok, _ = wait_for(s, lambda: all_status(s, "f2e2", "TOO_LARGE"), 10)
    check("a room over the cap reads too large", ok, json.dumps(status(s, "f2e2")))
    s.config("greenhouseMaxRadius", 32)
    s.config("greenhouseMaxHeight", 34)
    s.c(f"setblock {F2_X + 40} {Y + 1} 40 stone")
    s.c(f"ccstress hang f2e3 {F2_X + 41} {Y + 1} 40 east")
    ok, _ = wait_for(s, lambda: all_status(s, "f2e3", "OUTDOOR"), 10)
    check("a hygrometer on an open wall reads outdoor", ok, json.dumps(status(s, "f2e3")))

    # f) greenhouses switched off and on at runtime
    s.config("greenhouseEnabled", "false")
    time.sleep(1.5)
    exp = s.c(f"cropclimates explain {F2_X + 3} {Y + 1} 3", quiet=True)
    check("greenhouses off: crops use the biome", "Enclosed" not in exp, exp.splitlines()[1] if exp else "")
    s.config("greenhouseEnabled", "true")
    time.sleep(1.5)
    exp = s.c(f"cropclimates explain {F2_X + 3} {Y + 1} 3", quiet=True)
    check("greenhouses on again: crops see the room", "Enclosed" in exp, exp.splitlines()[1] if exp else "")

    # g) humidity arithmetic in the desert (biome 0.0): clamp(base + k * net / interior)
    gx = F2_X + 150
    s.c(f"ccstress build greenhouse f2g {gx} {Y} 0 10 10 4 wheat 0")
    ok, _ = wait_for(s, lambda: all_status(s, "f2g", "GREENHOUSE"), 10)
    st = status(s, "f2g")[0]
    interior = st["size"]
    k = 4.0
    expect = min(1.0, k * 9 / interior)  # 9 water cells on the lz=4 row (the walkway column has none)
    check("room humidity = k * water / interior", abs(st["humidity"] - expect) < 1e-6,
          f"size={interior} humidity={st['humidity']:.5f} expected={expect:.5f} base={st['base']}")
    for dz in range(0, 5):
        s.c(f"setblock {gx + 11} {Y + 1 + (dz % 3)} {1 + dz} wet_sponge", quiet=True)
    ok, _ = wait_for(s, lambda: abs(status(s, "f2g")[0]["humidity"] - min(1.0, k * 14 / interior)) < 1e-6, 10)
    check("wet sponges in the wall add +1 each", ok, f"humidity={status(s, 'f2g')[0]['humidity']:.5f} "
                                                     f"expected={min(1.0, k * 14 / interior):.5f}")
    for dz in range(0, 3):
        s.c(f"setblock {gx} {Y + 3} {7 + dz} sponge", quiet=True)
    ok, _ = wait_for(s, lambda: abs(status(s, "f2g")[0]["humidity"] - min(1.0, max(0.0, k * 11 / interior))) < 1e-6, 10)
    check("dry sponges in the wall subtract 1 each", ok, f"humidity={status(s, 'f2g')[0]['humidity']:.5f} "
                                                        f"expected={min(1.0, k * 11 / interior):.5f}")
    rep = s.c(f"cropclimates explain {gx + 4} {Y + 1} 2", quiet=True)
    check("Soil Tester shows the room humidity", f"{round(status(s, 'f2g')[0]['humidity'] * 100)}%" in rep,
          " | ".join(rep.splitlines()[1:3]))

    # h) chunk unload and reload keep the room
    release(s, F2_X - 16, -16, F2_X + 330, 120)
    time.sleep(6)
    unloaded = s.json("ccstress stats")["minecraft:overworld"]
    force(s, F2_X - 16, -16, F2_X + 330, 120)
    ok, _ = wait_for(s, lambda: all_status(s, "f2a", "GREENHOUSE"), 20)
    check("room survives its chunks unloading", ok,
          f"while unloaded: {unloaded.get('loadedHygrometerEntities')} loaded entities, rooms={unloaded.get('rooms')}")

    out = out_dir("F2-lifecycle")
    (out / "checks.json").write_text(json.dumps(CHECKS, indent=1))
    log(f"F2: {sum(c['ok'] for c in CHECKS)}/{len(CHECKS)} passed")


# ------------------------------------------------ F3 rain, position, regression

F3_X = 42000
RAIN = "\U0001F327"


def humidity_line(report):
    for line in report.splitlines():
        if "Humidity" in line:
            return line.strip("│ ").strip()
    return ""


def f3(s):
    s.rules(3)
    CHECKS.clear()
    force(s, F3_X - 16, -16, F3_X + 200, 100)
    biome(s, F3_X - 16, -16, F3_X + 99, 100, "minecraft:jungle")
    biome(s, F3_X + 100, -16, F3_X + 200, 100, "minecraft:desert")
    s.c("gamerule randomTickSpeed 0", quiet=True)

    # Rain reaches crops but not bamboo (motion-blocking), nor the farmland a player stands on.
    s.c(f"ccstress build field f3rain {F3_X} {Y} 0 40 20 wheat+bamboo+cactus+sugar_cane 0 20")
    s.c(f"setblock {F3_X + 25} {Y + 2} 2 bamboo", quiet=True)  # a 2-tall bamboo column
    s.c("weather rain", quiet=True)
    time.sleep(1)
    wheat = s.c(f"cropclimates explain {F3_X + 2} {Y + 1} 2", quiet=True)
    bamboo = s.c(f"cropclimates explain {F3_X + 12} {Y + 1} 2", quiet=True)
    check("rain reaches an open-air wheat crop", RAIN in humidity_line(wheat), humidity_line(wheat))
    check("BUG: rain never reaches bamboo (it blocks motion)", RAIN not in humidity_line(bamboo), humidity_line(bamboo))
    farmland = s.c(f"cropclimates explain {F3_X + 2} {Y} 2", quiet=True)
    check("BUG: standing on farmland (feet cell = farmland) sees no rain", RAIN not in humidity_line(farmland),
          humidity_line(farmland))
    cactus_top = s.c(f"cropclimates explain {F3_X + 20} {Y + 1} 0", quiet=True)
    above_cactus = s.c(f"cropclimates explain {F3_X + 20} {Y + 2} 0", quiet=True)
    check("BUG: Soil Tester on cactus sees no rain, but growth is scored one block up where it rains",
          RAIN not in humidity_line(cactus_top) and RAIN in humidity_line(above_cactus),
          f"cactus: {humidity_line(cactus_top)} | above: {humidity_line(above_cactus)}")
    s.c("weather clear", quiet=True)

    # Sky penalty: stone roof vs glass roof.
    s.c(f"setblock {F3_X + 3} {Y + 3} 5 stone", quiet=True)
    under_stone = s.c(f"cropclimates explain {F3_X + 3} {Y + 1} 5", quiet=True)
    check("a stone roof costs the sky penalty (no sunlight mark)", "◯" in under_stone,
          [l for l in under_stone.splitlines() if "Sunlight" in l][:1])
    s.c(f"setblock {F3_X + 3} {Y + 3} 5 glass", quiet=True)
    under_glass = s.c(f"cropclimates explain {F3_X + 3} {Y + 1} 5", quiet=True)
    check("a glass roof keeps sunlight", "☀" in under_glass, [l for l in under_glass.splitlines() if "Sunlight" in l][:1])

    # Hygrometers and water: Cold Sweat blacklists water for heat spread, so water is not room interior.
    s.c(f"ccstress build greenhouse f3water {F3_X + 50} {Y} 40 6 6 5 wheat 0")
    s.c(f"fill {F3_X + 51} {Y + 1} 41 {F3_X + 56} {Y + 4} 46 water", quiet=True)
    time.sleep(2)
    s.c(f"ccstress hang f3under {F3_X + 51} {Y + 2} 43 east")
    ok, _ = wait_for(s, lambda: status(s, "f3under") and status(s, "f3under")[0]["status"] != "SCANNING", 10)
    check("a hygrometer hung underwater can never make a greenhouse", status(s, "f3under")[0]["status"] == "TOO_SMALL",
          json.dumps(status(s, "f3under")))

    # Regression: hostile desert crops lose stages; saplings go stage 1 -> 0 -> dead bush.
    s.config("regressionChance", 1.0)
    s.c(f"ccstress build field f3reg {F3_X + 110} {Y} 0 40 20 wheat+cherry_sapling 5 20", quiet=True)
    s.c(f"fill {F3_X + 130} {Y + 1} 0 {F3_X + 149} {Y + 1} 19 cherry_sapling[stage=1] replace cherry_sapling", quiet=True)
    s.config("tempReadsPerTick", 100000)
    s.c(f"ccstress growth track f3reg {F3_X + 110} {Y} 0 {F3_X + 149} {Y + 3} 19", quiet=True)
    s.config("tempReadsPerTick", 12)
    s.c("gamerule randomTickSpeed 200", quiet=True)
    time.sleep(20)
    s.c("gamerule randomTickSpeed 0", quiet=True)
    stats = s.json("ccstress stats")
    dead = s.c(f"execute store result score @p dummy run fill {F3_X + 130} {Y + 1} 0 {F3_X + 149} {Y + 1} 19 dead_bush replace dead_bush",
               quiet=True)
    wheat5 = s.c(f"fill {F3_X + 110} {Y + 1} 0 {F3_X + 129} {Y + 1} 19 wheat[age=5] replace wheat[age=5]", quiet=True)
    check("hostile crops lose stages", stats["counters"]["regressionsApplied"] > 0,
          f"regressionsApplied={stats['counters']['regressionsApplied']} vetoes={stats['counters']['vetoes']}; wheat still at age 5: {wheat5.strip()}")
    check("hostile saplings die back to dead bushes", "Successfully filled" in dead and not dead.strip().endswith(" 0 blocks"),
          dead.strip())
    s.config("regressionChance", 0.02)
    s.c("gamerule randomTickSpeed 3", quiet=True)

    out = out_dir("F3-rain-position")
    (out / "checks.json").write_text(json.dumps(CHECKS, indent=1))
    log(f"F3: {sum(c['ok'] for c in CHECKS)}/{len(CHECKS)} checks as expected")
    release(s, F3_X - 16, -16, F3_X + 200, 100)


# ------------------------------------------------------------- S1 big fields

S1_X = 3000
S1_TYPES = ("wheat+carrots+potatoes+beetroots+melon_stem+pumpkin_stem+sweet_berry+sugar_cane+oak_sapling+birch_sapling"
            "+torchflower+bamboo+cactus")


def s1_build(s, both):
    s.c(f"ccstress build field S1a {S1_X} {Y} 0 230 230 {S1_TYPES} -1 32")
    if both:
        s.c(f"ccstress build field S1b {S1_X} {Y} 300 230 230 {S1_TYPES} -1 32")


def s1(s):
    """Two 230x230 fields (~100k plants): plains (friendly) and desert (hostile)."""
    s.rules(0)
    force(s, S1_X - 8, -8, S1_X + 232, 536)
    biome(s, S1_X - 8, -8, S1_X + 232, 240, "minecraft:plains")
    biome(s, S1_X - 8, 290, S1_X + 232, 536, "minecraft:desert")
    for both in (False, True):
        tag = "2fields" if both else "1field"
        if not both:
            s.c(f"ccstress build clear {S1_X} {Y + 1} 300 {S1_X + 229} {Y + 32} 529", quiet=True)
        for rts, secs in ((3, 60), (30, 60), (300, 30), (1000, 20)):
            s.c("gamerule randomTickSpeed 0", quiet=True)
            s1_build(s, both)
            s.c("ccstress stats reset", quiet=True)
            s.c(f"gamerule randomTickSpeed {rts}", quiet=True)
            time.sleep(5)
            s.window(f"S1-{tag}-rts{rts}", secs)
            s.window(f"S1-{tag}-rts{rts}-prof", secs, profile=True)
    # Rain: the outdoor humidity path does extra work while it rains.
    s.c("gamerule randomTickSpeed 0", quiet=True)
    s1_build(s, True)
    s.c("weather rain", quiet=True)
    s.c("gamerule randomTickSpeed 300", quiet=True)
    time.sleep(5)
    s.window("S1-2fields-rts300-rain", 30)
    s.window("S1-2fields-rts300-rain-prof", 30, profile=True)
    s.c("weather clear", quiet=True)
    s.c("gamerule randomTickSpeed 3", quiet=True)
    release(s, S1_X - 8, -8, S1_X + 232, 536)


# ------------------------------------------------------ S2 the max greenhouse

S2_X = 5000
S2_TYPES = ("wheat+carrots+potatoes+beetroots+melon_stem+pumpkin_stem+sweet_berry+sugar_cane+bamboo+mega_jungle"
            "+oak_sapling")


def wait_quiet(s, dim="minecraft:overworld", timeout=600):
    """Waits until the greenhouse scan queue is empty; returns seconds taken."""
    ok, t = wait_for(s, lambda: s.json("ccstress stats").get(dim, {}).get("queue", 0) == 0, timeout, 0.5)
    return t


def dat_size():
    f = SERVER_WORLD / "data" / "crop_climates_greenhouses.dat"
    return f.stat().st_size if f.exists() else 0


def save_window(s, label):
    """A short window around save-all flush, to catch the save cost as a tick spike."""
    s.c(f"ccstress mspt start {label}{TAG}", quiet=True)
    time.sleep(2)
    s.c("save-all flush", quiet=True)
    time.sleep(3)
    stats = s.json("ccstress mspt stop")
    out = out_dir(label)
    stats["datBytes"] = dat_size()
    (out / "mspt.json").write_text(json.dumps(stats, indent=1))
    log(f"window {label}: max={stats.get('maxMs')} save={stats['counters'].get('saveMaxMs')}ms dat={stats['datBytes']}B")
    return stats


def s2(s):
    """A 65x65x34 greenhouse (143,650 cells, the default cap) packed with growing plants, plus a
    five-storey 60x60 farm."""
    s.rules(0)
    force(s, S2_X - 8, -8, S2_X + 80, 80)
    biome(s, S2_X - 8, -8, S2_X + 80, 80, "minecraft:jungle")
    s.c("ccstress stats reset", quiet=True)
    s.c(f"ccstress build greenhouse S2 {S2_X} {Y} 0 65 65 34 {S2_TYPES} -1")
    t = wait_quiet(s)
    st = s.json("ccstress status S2")
    stats = s.json("ccstress stats")
    log(f"S2 first scan settled in {t:.1f}s: {json.dumps(st)} counters={json.dumps(stats['counters'])}")
    # Let it grow: trees, cane, bamboo, fruit.
    s.c("gamerule randomTickSpeed 300", quiet=True)
    time.sleep(60)
    for rts, secs in ((3, 60), (30, 60), (300, 30)):
        s.c(f"gamerule randomTickSpeed {rts}", quiet=True)
        time.sleep(5)
        s.window(f"S2-brim-rts{rts}", secs)
        s.window(f"S2-brim-rts{rts}-prof", secs, profile=True)
    s.c("gamerule randomTickSpeed 3", quiet=True)
    log("S2 status after growth: " + s.c("ccstress status S2", quiet=True))
    save_window(s, "S2-save")
    # Five storeys of crops under one roof.
    force(s, S2_X + 192, -8, S2_X + 272, 80)
    biome(s, S2_X + 192, -8, S2_X + 272, 80, "minecraft:plains")
    s.c(f"ccstress build tiers S2t {S2_X + 200} {Y} 0 60 5 6 wheat+carrots+potatoes+beetroots -1")
    wait_quiet(s)
    log("S2 tiers status: " + s.c("ccstress status S2t", quiet=True))
    release(s, S2_X - 8, -8, S2_X + 80, 80)
    for rts, secs in ((30, 60), (300, 30)):
        s.c(f"gamerule randomTickSpeed {rts}", quiet=True)
        time.sleep(5)
        s.window(f"S2-tiers-rts{rts}", secs)
        s.window(f"S2-tiers-rts{rts}-prof", secs, profile=True)
    s.c("gamerule randomTickSpeed 3", quiet=True)
    release(s, S2_X + 192, -8, S2_X + 272, 80)


# -------------------------------------------------------- S3 many greenhouses

S3_GRIDS = [(256, 16, 7000), (1024, 32, 7200), (2025, 45, 7600)]


def s3(s):
    """Grids of small (5x5x4) greenhouses, each with a hygrometer and 20 crops."""
    s.rules(0)
    for n, side, x in S3_GRIDS:
        extent = side * 9
        force(s, x - 8, -8, x + extent + 8, extent + 8)
        biome(s, x - 8, -8, x + extent + 8, extent + 8, "minecraft:plains")
        s.c("ccstress stats reset", quiet=True)
        t0 = time.time()
        s.c(f"ccstress build grid S3-{n} {x} {Y} 0 {side} {side} 5 5 4 2 wheat+carrots+potatoes+beetroots -1")
        t = wait_quiet(s)
        stats = s.json("ccstress stats")
        log(f"S3-{n}: built+scanned in {time.time() - t0:.1f}s (scan catch-up {t:.1f}s) "
            f"state={json.dumps(stats.get('minecraft:overworld'))} counters={json.dumps(stats['counters'])}")
        for rts, secs in ((3, 60), (30, 60)):
            s.c(f"gamerule randomTickSpeed {rts}", quiet=True)
            time.sleep(5)
            s.window(f"S3-{n}-rts{rts}", secs)
            s.window(f"S3-{n}-rts{rts}-prof", secs, profile=True)
        if n == 2025:
            # Every roof opens at once, then closes: how long until the registry catches up?
            s.c("gamerule randomTickSpeed 3", quiet=True)
            s.c(f"ccstress mspt start S3-2025-roofs{TAG}", quiet=True)
            t0 = time.time()
            s.c(f"ccstress roofs S3-{n} open")
            ok, t_open = wait_for(s, lambda: s.json("ccstress stats")["minecraft:overworld"]["status"].get("OUTDOOR", 0) >= n,
                                  600, 0.5)
            s.c(f"ccstress roofs S3-{n} close")
            ok2, t_close = wait_for(s, lambda: s.json("ccstress stats")["minecraft:overworld"]["status"].get("GREENHOUSE", 0) >= n,
                                    600, 0.5)
            stats = s.json("ccstress mspt stop")
            out = out_dir("S3-2025-roofs")
            stats["allLeakedSeconds"] = t_open if ok else None
            stats["allResealedSeconds"] = t_close if ok2 else None
            (out / "mspt.json").write_text(json.dumps(stats, indent=1))
            log(f"S3 roofs: all {n} leaked after {t_open:.1f}s ({ok}), all resealed after {t_close:.1f}s ({ok2}); "
                f"mean={stats.get('meanMs')} p99={stats.get('p99Ms')} max={stats.get('maxMs')}")
            save_window(s, "S3-2025-save")
        release(s, x - 8, -8, x + extent + 8, extent + 8)
    s.c("gamerule randomTickSpeed 3", quiet=True)


# ------------------------------------------------ S4 pathological hygrometers

S4_X = 9000


def latency(s, label, index=0, timeout=900):
    s.c(f"ccstress latency {label} {index}", quiet=True)
    ok, t = wait_for(s, lambda: s.c("ccstress latency result", quiet=True).strip() not in ("running", "none"), timeout, 0.5)
    return json.loads(s.c("ccstress latency result", quiet=True))


def s4(s):
    s.rules(3)
    force(s, S4_X - 8, -8, S4_X + 136, 200)
    biome(s, S4_X - 8, -8, S4_X + 136, 200, "minecraft:plains")
    # A normal greenhouse to watch for starvation.
    s.c(f"ccstress build greenhouse S4ref {S4_X + 10} {Y} 150 9 9 5 wheat 0")
    wait_quiet(s)
    base = latency(s, "S4ref")
    log(f"S4 reference latency with an idle scanner: {base}")
    # One hygrometer in a big sealed cave (576k cells > cap): too-large retries.
    s.c(f"ccstress build cave {S4_X} 0 0 120 40 120")
    s.c("ccstress stats reset", quiet=True)
    s.window("S4-cave1", 120)
    # Ten more hygrometers along the same cave wall.
    for i in range(10):
        s.c(f"ccstress hang S4cave {S4_X} 3 {10 + i * 10} east", quiet=True)
    s.window("S4-cave11", 120)
    s.window("S4-cave11-prof", 60, profile=True)
    lat = latency(s, "S4ref")
    log(f"S4 reference latency while 11 cave hygrometers loop: {lat}")
    (out_dir("S4-cave11") / "latency.json").write_text(json.dumps({"idle": base, "cave11": lat}))
    # Clear the cave hygrometers.
    s.c(f"kill @e[type=crop_climates:hygrometer,x={S4_X},y=0,z=0,dx=10,dy=40,dz=130]")
    # A sealed room straddling unloaded chunks: unloaded retries every 200 ticks.
    s.c(f"ccstress build greenhouse S4edge {S4_X + 40} {Y} 180 30 60 4 wheat 0")
    wait_quiet(s)
    release(s, S4_X - 8, 208, S4_X + 136, 260)
    time.sleep(10)
    s.window("S4-edge", 120)
    log("S4 edge status: " + s.c("ccstress status S4edge", quiet=True))
    release(s, S4_X - 8, -8, S4_X + 136, 200)
    # The Nether: open caverns never seal.
    s.c("execute in minecraft:the_nether run ccstress ticket add -160 -160 160 160")
    s.c("execute in minecraft:the_nether run ccstress build nether -100 0 10 20")
    time.sleep(5)
    s.window("S4-nether10", 120)
    s.window("S4-nether10-prof", 60, profile=True)
    log("S4 nether state: " + json.dumps(s.json("ccstress stats").get("minecraft:the_nether")))
    s.c("execute in minecraft:the_nether run ccstress hygrometers kill")
    s.c("execute in minecraft:the_nether run ccstress ticket remove -160 -160 160 160")


# ------------------------------------------------------------------ S5 churn

S5_X = 11000


def s5(s):
    s.rules(3)
    # Block churn far from any greenhouse: what every block change pays for the mod's hooks.
    force(s, S5_X - 8, -8, S5_X + 104, 104)
    s.c(f"ccstress build clear {S5_X} {Y + 1} 0 {S5_X + 99} {Y + 20} 99", quiet=True)
    for per_tick in (500, 2000):
        s.c(f"ccstress churn far {per_tick} {S5_X} {Y + 1} 0 {S5_X + 99} {Y + 20} 99")
        time.sleep(3)
        s.window(f"S5-far-{per_tick}", 30)
        s.window(f"S5-far-{per_tick}-prof", 30, profile=True)
        s.c("ccstress churn stop")
    release(s, S5_X - 8, -8, S5_X + 104, 104)
    # Harvest-and-replant inside the max greenhouse (S2): every change is a block swap.
    force(s, S2_X - 8, -8, S2_X + 80, 80)
    wait_quiet(s)
    for per_tick in (5, 50):
        s.c(f"ccstress churn room crops S2 {per_tick}")
        time.sleep(3)
        s.window(f"S5-S2crops-{per_tick}", 60)
        s.window(f"S5-S2crops-{per_tick}-prof", 30, profile=True)
        s.c("ccstress churn stop")
    # Player spam-clicking the max room's hygrometer once a second.
    s.c("ccstress churn room click S2 20")
    s.window("S4-S2click", 60)
    s.c("ccstress churn stop")
    release(s, S2_X - 8, -8, S2_X + 80, 80)


# ---------------------------------------------- S7 scanner budget trade-off

def s7(s):
    s.rules(3)
    force(s, S2_X - 8, -8, S2_X + 80, 80)
    wait_quiet(s)
    rows = []
    for budget in (512, 2048, 8192):
        s.config("greenhouseCellsPerTick", budget)
        s.c("ccstress churn room walls S2 1")
        time.sleep(3)
        stats = s.window(f"S7-budget{budget}", 60)
        s.c("ccstress churn stop")
        wait_quiet(s)
        lat = latency(s, "S2")
        rows.append({"budget": budget, "latency": lat, "meanMs": stats.get("meanMs"), "p99Ms": stats.get("p99Ms"),
                     "maxMs": stats.get("maxMs")})
        log(f"S7 budget {budget}: {rows[-1]}")
    s.config("greenhouseCellsPerTick", 2048)
    (out_dir("S7-budget") / "summary.json").write_text(json.dumps(rows, indent=1))
    release(s, S2_X - 8, -8, S2_X + 80, 80)


SERVER_WORLD = RESULTS.parent / "server" / "world"

SCENARIOS = {"s0": s0, "f1": f1, "f2": f2, "f3": f3, "s1": s1, "s2": s2, "s3": s3, "s4": s4, "s5": s5, "s7": s7}

if __name__ == "__main__":
    session = Session()
    for name in sys.argv[1:]:
        log(f"=== scenario {name}")
        SCENARIOS[name](session)
