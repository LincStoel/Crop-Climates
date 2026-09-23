"""Functional check for the block blacklist, with StressBot as the player. Edits the
server's crop_climates-server.toml on disk (as a pack author would) and checks that a
blacklisted block drops out of every part of the mod, live, without a restart: the
explain report, growth governance, wilting, the planting advancements, the Alt tooltip
and Jade (a screenshot to look at). Needs the stress server running; launches the
stress client.

    python tools/stress/blacklist_check.py

Results: run-stress/results/F6-blacklist/checks.json, screenshots in
run-stress/client/screenshots/blacklist-*.png.
"""
import json
import re
import time

from harness import Session, SERVER, log, out_dir
from scenarios import force, release, wait_for, Y
from client_session import BOT, CLIENT, launch, leave

OUT = out_dir("F6-blacklist") / "checks.json"
TOML = SERVER / "config" / "crop_climates-server.toml"
SERVER_LOG = SERVER / "logs" / "latest.log"
TIPS = CLIENT / "stress-tooltips.txt"
CHECKS = []

# A fresh strip well clear of the scenario areas: a flower forest plot and a desert plot.
X0 = 9400
GOOD = (X0 + 15, 20)    # flower forest: wheat and beetroot thrive
BAD = (X0 + 75, 20)     # desert: both are near the growth floor
AREA = (X0 - 8, -8, X0 + 100, 50)


def check(name, ok, detail=""):
    CHECKS.append({"check": name, "ok": bool(ok), "detail": detail})
    log(f"  [{'PASS' if ok else 'FAIL'}] {name} {detail}")
    OUT.write_text(json.dumps(CHECKS, indent=1), encoding="utf-8")


def set_blacklist(entries):
    """Rewrites [blacklist] blocks in the toml and waits for the reload's resolve log line."""
    text = TOML.read_text(encoding="utf-8")
    new = re.sub(r"(\[blacklist\][^\[]*?blocks\s*=\s*)\[[^\]]*\]",
                 lambda m: m.group(1) + "[" + ", ".join(f'"{e}"' for e in entries) + "]", text, count=1, flags=re.S)
    if new == text and entries:
        raise SystemExit("could not find [blacklist] blocks in " + str(TOML))
    mark = log_size()
    TOML.write_text(new, encoding="utf-8")
    ok, t = wait_for(None, lambda_log(mark, "blacklisted"), 20, 0.5)
    return ok, new_log(mark)


def log_size():
    return SERVER_LOG.stat().st_size


def new_log(mark):
    with open(SERVER_LOG, "rb") as f:
        f.seek(mark)
        return f.read().decode("utf-8", "replace")


def lambda_log(mark, needle):
    return lambda: needle in new_log(mark)


def loaded_line(text):
    lines = [l for l in text.splitlines() if "crop_climates: loaded" in l]
    return lines[-1].split("crop_climates: ", 1)[-1] if lines else "(no resolve line)"


def plot(s, x, z, biome):
    s.c(f"fillbiome {x - 10} -64 {z - 10} {x + 10} 319 {z + 10} minecraft:{biome}", quiet=True)


def crop(s, x, z, block, age):
    """Moist farmland with water beside it, and the crop at the given age on top."""
    s.c(f"setblock {x} {Y} {z} farmland[moisture=7]", quiet=True)
    s.c(f"setblock {x} {Y} {z + 1} water", quiet=True)
    s.c(f"setblock {x} {Y + 1} {z} minecraft:{block}[age={age}]", quiet=True)


def age(s, x, z, block, top):
    for a in range(top + 1):
        if "passed" in s.c(f"execute if block {x} {Y + 1} {z} minecraft:{block}[age={a}]", quiet=True).lower():
            return a
    return None


def explain(s, x, z):
    return s.c(f"cropclimates explain {x} {Y + 1} {z}", quiet=True)


def governed(s, x, z):
    return "Growth" in explain(s, x, z)


def growth_row(s, x, z):
    rows = [l.strip("│ ").strip() for l in explain(s, x, z).splitlines() if "Growth" in l]
    return rows[0] if rows else "(no growth row)"


def tooltip(s, item, label):
    """Captures the Alt tooltip twice: the verdict line answers the first request."""
    before = TIPS.read_text(encoding="utf-8") if TIPS.exists() else ""
    s.c(f"ccstress instruct {BOT} log {label}", quiet=True)
    s.c(f"ccstress instruct {BOT} tooltip {item}", quiet=True)
    time.sleep(2)
    s.c(f"ccstress instruct {BOT} tooltip {item}", quiet=True)
    time.sleep(1.5)
    after = TIPS.read_text(encoding="utf-8") if TIPS.exists() else ""
    new = after[len(before):]
    # Only the last capture: it is the one with the verdict line.
    blocks = [b for b in new.split(label) if b.strip()]
    return blocks[-1].strip() if blocks else new.strip()


def jade_shot(s, x, z, name):
    s.c(f"tp {BOT} {x + 0.5} {Y + 2.4} {z + 0.5} 0 90", quiet=True)
    s.c(f"ccstress fly {BOT}", quiet=True)
    time.sleep(3)
    s.c(f"ccstress instruct {BOT} shot blacklist-{name}", quiet=True)
    time.sleep(2)


def stand_on_good(s):
    s.c(f"tp {BOT} {GOOD[0] + 3.5} {Y + 1} {GOOD[1] + 3.5} 0 30", quiet=True)
    s.c(f"ccstress fly {BOT}", quiet=True)
    time.sleep(2)


def main(s):
    s.rules(3)
    original = TOML.read_text(encoding="utf-8")
    if "[blacklist]" not in original:
        check("the server config has a [blacklist] section", False, "restart the server on the new build")
        return
    ok, text = set_blacklist([])
    check("an empty blacklist resolves on save", ok, loaded_line(text))

    plot(s, *GOOD, "flower_forest")
    plot(s, *BAD, "desert")
    gx, gz = GOOD
    bx, bz = BAD
    crop(s, gx, gz, "wheat", 3)
    crop(s, gx + 3, gz, "beetroots", 1)
    time.sleep(11)  # past the temperature cache's lifetime, so the new biomes are read

    proc = launch(s)
    if proc is None:
        return
    try:
        s.c(f"gamemode creative {BOT}", quiet=True)
        s.c(f"effect give {BOT} minecraft:night_vision infinite 0 true", quiet=True)

        # ---- Baseline: everything governed.
        check("baseline: wheat is governed", governed(s, gx, gz), growth_row(s, gx, gz))
        stand_on_good(s)
        tip = tooltip(s, "minecraft:wheat_seeds", "bl-tip-wheat-before")
        check("baseline: wheat seeds show growing conditions", "Growing conditions" in tip, tip.replace("\n", " / ")[:200])
        jade_shot(s, gx, gz, "jade-wheat-before")

        # ---- Blacklist wheat by id.
        ok, text = set_blacklist(["minecraft:wheat"])
        check("saving 'minecraft:wheat' re-resolves live", ok and "1 blacklisted" in text, loaded_line(text))
        check("wheat is no longer governed (no growth row)", not governed(s, gx, gz), growth_row(s, gx, gz))
        check("explain still shows the local climate on it", "Temp" in explain(s, gx, gz))
        check("beetroot is still governed", governed(s, gx + 3, gz), growth_row(s, gx + 3, gz))
        stand_on_good(s)
        tip = tooltip(s, "minecraft:wheat_seeds", "bl-tip-wheat-after")
        check("wheat seeds' tooltip has no growing conditions", "Growing conditions" not in tip and "Hold" not in tip,
              tip.replace("\n", " / ")[:200])
        tip = tooltip(s, "minecraft:beetroot_seeds", "bl-tip-beet-after")
        check("beetroot seeds' tooltip still has them", "Growing conditions" in tip, tip.replace("\n", " / ")[:200])
        jade_shot(s, gx, gz, "jade-wheat-after")
        jade_shot(s, gx + 3, gz, "jade-beetroot-after")

        # Planting in the desert: beetroot earns Desperate conditions, blacklisted wheat never does.
        s.c(f"advancement revoke {BOT} only crop_climates:desperate_conditions", quiet=True)
        for x in (bx, bx + 3):
            s.c(f"setblock {x} {Y} {bz} farmland[moisture=7]", quiet=True)
            s.c(f"setblock {x} {Y + 1} {bz} air", quiet=True)
        s.c(f"ccstress useitem {BOT} minecraft:wheat_seeds {bx} {Y} {bz} up")
        time.sleep(2)
        has = lambda: "passed" in s.c(f"execute if entity @a[name={BOT},advancements={{crop_climates:desperate_conditions=true}}]",
                                      quiet=True).lower()
        planted = "passed" in s.c(f"execute if block {bx} {Y + 1} {bz} minecraft:wheat", quiet=True).lower()
        check("planting blacklisted wheat in a desert does not grant 'Desperate conditions'", planted and not has(),
              f"planted={planted}")
        s.c(f"ccstress useitem {BOT} minecraft:beetroot_seeds {bx + 3} {Y} {bz} up")
        ok, _ = wait_for(s, has, 5, 0.5)
        check("control: planting beetroot there does grant it", ok, growth_row(s, bx + 3, bz))

        # Growth and wilting in the desert, fast random ticks, every failed roll wilting.
        crop(s, bx, bz, "wheat", 3)
        crop(s, bx + 3, bz, "beetroots", 2)
        s.config("regressionChance", 1.0)
        s.c("gamerule randomTickSpeed 200", quiet=True)
        time.sleep(15)
        s.c("gamerule randomTickSpeed 3", quiet=True)
        s.config("regressionChance", 0.02)
        wheat_age, beet_age = age(s, bx, bz, "wheat", 7), age(s, bx + 3, bz, "beetroots", 3)
        check("blacklisted wheat grows at vanilla speed in a desert and never wilts", wheat_age is not None and wheat_age > 3,
              f"age 3 -> {wheat_age}")
        check("control: governed beetroot there does not grow (wilts or holds)", beet_age is not None and beet_age <= 2,
              f"age 2 -> {beet_age}")

        # ---- A tag entry, plus an entry that is not a block.
        ok, text = set_blacklist(["#minecraft:crops", "minecraft:not_a_block"])
        check("a tag entry re-resolves live", ok, loaded_line(text))
        check("an unknown entry is warned about", "not_a_block is not a block" in text)
        check("#minecraft:crops excludes beetroot too", not governed(s, gx + 3, gz), growth_row(s, gx + 3, gz))
        check("... and wheat", not governed(s, gx, gz))

        # ---- Cleared again: back to normal.
        ok, text = set_blacklist([])
        check("clearing the blacklist re-resolves live", ok and "0 blacklisted" in text, loaded_line(text))
        check("wheat is governed again", governed(s, gx, gz), growth_row(s, gx, gz))
        stand_on_good(s)
        tip = tooltip(s, "minecraft:wheat_seeds", "bl-tip-wheat-restored")
        check("wheat seeds' tooltip is back", "Growing conditions" in tip, tip.replace("\n", " / ")[:200])
        jade_shot(s, gx, gz, "jade-wheat-restored")
    finally:
        s.c("gamerule randomTickSpeed 3", quiet=True)
        s.config("regressionChance", 0.02)
        leave(s, proc)
    log(f"blacklist check: {sum(c['ok'] for c in CHECKS)}/{len(CHECKS)} passed")


if __name__ == "__main__":
    session = Session()
    force(session, *AREA)
    try:
        main(session)
    finally:
        release(session, *AREA)
