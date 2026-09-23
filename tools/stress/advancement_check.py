"""Functional check for the advancements, with StressBot as the player: each is revoked, then
earned (or deliberately not earned) through the real code path. Needs the stress server
running with S4's area built (S4ref greenhouse); launches the stress client.

    python tools/stress/advancement_check.py

Results: run-stress/results/F5-advancements/checks.json.
"""
import json
import time

from harness import Session, log, out_dir
from scenarios import force, status, wait_for, S4_X, Y
from client_session import BOT, CLIENT, launch, leave

OUT = out_dir("F5-advancements") / "checks.json"
CHAT = CLIENT / "stress-chat.txt"
IDS = ["greenhouse", "soiled_it", "thriving", "desperate_conditions", "invasive"]
CHECKS = []


def check(name, ok, detail=""):
    CHECKS.append({"check": name, "ok": bool(ok), "detail": detail})
    log(f"  [{'PASS' if ok else 'FAIL'}] {name} {detail}")
    OUT.write_text(json.dumps(CHECKS, indent=1), encoding="utf-8")


def chat():
    return CHAT.read_text(encoding="utf-8") if CHAT.exists() else ""


def has(s, adv):
    return "passed" in s.c(f"execute if entity @a[name={BOT},advancements={{crop_climates:{adv}=true}}]", quiet=True).lower()


def revoke_all(s):
    for adv in IDS:
        s.c(f"advancement revoke {BOT} only crop_climates:{adv}", quiet=True)


def plot(s, x, z, biome):
    """Farmland at (x, Y, z) in the middle of a 21x21 patch of the given biome, air above."""
    s.c(f"fillbiome {x - 10} -64 {z - 10} {x + 10} 319 {z + 10} minecraft:{biome}", quiet=True)
    s.c(f"setblock {x} {Y} {z} farmland", quiet=True)
    s.c(f"setblock {x} {Y + 1} {z} air", quiet=True)


def explain(s, x, z):
    lines = s.c(f"cropclimates explain {x} {Y + 1} {z}", quiet=True).splitlines()
    return " | ".join(l.strip("│ ").strip() for l in lines if "×" in l or "Growth" in l or "rate" in l.lower())[:160]


def main(s):
    if CHAT.exists():
        CHAT.unlink()
    proc = launch(s)
    if proc is None:
        return
    s.rules(3)
    s.c(f"gamemode creative {BOT}")
    s.c(f"tp {BOT} {S4_X + 10} 5 30")
    s.c(f"ccstress fly {BOT}")
    revoke_all(s)
    time.sleep(1)
    check("all five start revoked", not any(has(s, a) for a in IDS))

    # Hygrometer? I hardly know her! - joining an existing greenhouse does not count, sealing a new one does.
    # (A rerun first takes down the hygrometers an earlier run hung at these spots.)
    for x, y, z in ((S4_X + 11, Y + 2, 159), (S4_X + 2, Y + 3, 168)):
        s.c(f"kill @e[type=crop_climates:hygrometer,x={x}.5,y={y}.5,z={z}.5,distance=..1]", quiet=True)
    time.sleep(1)
    s.c(f"ccstress use adv_join {BOT} {S4_X + 10} {Y + 2} 159 east")
    time.sleep(5)
    check("hanging a hygrometer in an existing greenhouse does not grant it",
          (status(s, "adv_join") or [{}])[-1].get("status") == "GREENHOUSE" and not has(s, "greenhouse"))
    s.c(f"fill {S4_X + 1} {Y + 1} 165 {S4_X + 7} {Y + 6} 171 glass hollow", quiet=True)
    s.c(f"ccstress use adv_new {BOT} {S4_X + 1} {Y + 3} 168 east")
    ok, t = wait_for(s, lambda: has(s, "greenhouse"), 15, 0.5)
    check("hanging one that seals a new greenhouse grants 'Hygrometer? I hardly know her!'", ok, f"after {t:.1f}s")

    # Thriving! / Desperate conditions, judged on planting. First the exact thresholds, with every
    # multiplier pinned through the config: 1.0 is not above 1, 0.5 is neither, 0.1 is at most 0.1.
    plot(s, S4_X + 15, 20, "flower_forest")
    plot(s, S4_X + 95, 20, "desert")
    for x in (S4_X + 12, S4_X + 18, S4_X + 21):
        s.c(f"setblock {x} {Y} 20 farmland", quiet=True)
        s.c(f"setblock {x} {Y + 1} 20 air", quiet=True)
    time.sleep(11)  # past the temperature cache's lifetime, so the new biomes are read
    for x, pinned in ((S4_X + 12, 1.0), (S4_X + 18, 0.5)):
        s.config("growthFloor", pinned)
        s.config("growthMax", pinned)
        s.c(f"ccstress useitem {BOT} minecraft:wheat_seeds {x} {Y} 20 up")
        time.sleep(2)
        check(f"a crop planted at exactly {pinned}x grants neither",
              not has(s, "thriving") and not has(s, "desperate_conditions"), explain(s, x, 20))
    s.config("growthFloor", 0.1)
    s.config("growthMax", 0.1)
    s.c(f"ccstress useitem {BOT} minecraft:wheat_seeds {S4_X + 21} {Y} 20 up")
    ok, _ = wait_for(s, lambda: has(s, "desperate_conditions"), 5, 0.5)
    check("a crop planted at exactly 0.1x grants 'Desperate conditions'", ok and not has(s, "thriving"), explain(s, S4_X + 21, 20))
    s.c(f"advancement revoke {BOT} only crop_climates:desperate_conditions", quiet=True)
    s.config("growthFloor", 0.005)
    s.config("growthMax", 1.25)

    # Then real conditions with the default settings.
    s.c(f"ccstress useitem {BOT} minecraft:wheat_seeds {S4_X + 15} {Y} 20 up")
    ok, _ = wait_for(s, lambda: has(s, "thriving"), 5, 0.5)
    check("wheat planted in a flower forest (above 1) grants 'Thriving!'", ok and not has(s, "desperate_conditions"),
          explain(s, S4_X + 15, 20))
    s.c(f"ccstress useitem {BOT} minecraft:beetroot_seeds {S4_X + 95} {Y} 20 up")
    ok, _ = wait_for(s, lambda: has(s, "desperate_conditions"), 5, 0.5)
    check("beetroot planted in a desert (at most 0.1) grants 'Desperate conditions'", ok, explain(s, S4_X + 95, 20))

    # Soiled it! - the Soil Tester on the ground does not count, on a crop it does.
    s.c(f"ccstress useitem {BOT} crop_climates:soil_tester {S4_X + 10} {Y} 20 up")
    time.sleep(2)
    check("the Soil Tester on bare ground does not grant it", not has(s, "soiled_it"))
    s.c(f"ccstress useitem {BOT} crop_climates:soil_tester {S4_X + 15} {Y + 1} 20 up")
    ok, _ = wait_for(s, lambda: has(s, "soiled_it"), 5, 0.5)
    check("the Soil Tester on a crop grants 'Soiled it!'", ok)

    # Invasive! - the Soil Tester on a mob. An iron golem: this server discards animals and
    # villagers (spawn-animals / spawn-npcs are off) and peaceful removes monsters.
    s.c(f"summon minecraft:iron_golem {S4_X + 30} {Y + 1} 30 {{NoAI:1b}}", quiet=True)
    time.sleep(1)
    reply = s.c(f"ccstress useentity {BOT} crop_climates:soil_tester {S4_X + 30} {Y + 1} 30 go")
    ok, _ = wait_for(s, lambda: has(s, "invasive"), 5, 0.5)
    check("the Soil Tester on a mob grants 'Invasive!'", ok, reply.strip())
    s.c(f"kill @e[type=minecraft:iron_golem,x={S4_X + 30},y={Y + 1},z=30,distance=..4]", quiet=True)

    time.sleep(2)
    text = chat()
    names = ["Hygrometer? I hardly know her!", "Thriving!", "Desperate conditions", "Soiled it!", "Invasive!"]
    missing = [n for n in names if f"[{n}]" not in text]
    check("each shows its title in the chat announcement", not missing, f"missing: {missing}" if missing else "")
    leave(s, proc)
    log(f"advancement check: {sum(c['ok'] for c in CHECKS)}/{len(CHECKS)} passed")


if __name__ == "__main__":
    session = Session()
    force(session, S4_X - 8, -8, S4_X + 136, 200)
    main(session)
