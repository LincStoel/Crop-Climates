"""Prepares run-stress/server and run-stress/client (idempotent).

Copies the runtime mods from run/mods (Cold Sweat, spark, Jade), writes a flat
stress world's server.properties with RCON bound to 127.0.0.1 and a random
password, accepts the EULA for this local dev server, and writes client
options that skip first-launch screens and uncap FPS.
"""
import pathlib
import secrets
import shutil
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
SRC_MODS = ROOT / "run" / "mods"
SERVER = ROOT / "run-stress" / "server"
CLIENT = ROOT / "run-stress" / "client"
WANTED = ("ColdSweat", "spark", "Jade")

FLAT = ('{"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:stone","height":124},'
        '{"block":"minecraft:dirt","height":3},{"block":"minecraft:grass_block","height":1}],'
        '"biome":"minecraft:plains","features":false,"lakes":false,"structure_overrides":[]}')


def copy_mods(dest):
    (dest / "mods").mkdir(parents=True, exist_ok=True)
    for jar in SRC_MODS.glob("*.jar"):
        if jar.name.startswith(WANTED):
            target = dest / "mods" / jar.name
            if not target.exists():
                shutil.copy2(jar, target)


def main():
    SERVER.mkdir(parents=True, exist_ok=True)
    CLIENT.mkdir(parents=True, exist_ok=True)
    copy_mods(SERVER)
    copy_mods(CLIENT)
    (SERVER / "eula.txt").write_text("eula=true\n", encoding="utf-8")
    props = SERVER / "server.properties"
    if not props.exists() or "--reset" in sys.argv:
        password = secrets.token_hex(16)
        props.write_text("\n".join([
            "online-mode=false",
            "enforce-secure-profile=false",
            "server-ip=127.0.0.1",
            "server-port=25565",
            "enable-rcon=true",
            "rcon.port=25575",
            f"rcon.password={password}",
            "broadcast-rcon-to-ops=false",
            "level-name=world",
            "level-seed=424242",
            "level-type=minecraft\\:flat",
            f"generator-settings={FLAT}",
            "generate-structures=false",
            "spawn-protection=0",
            "max-tick-time=-1",
            "view-distance=10",
            "simulation-distance=10",
            "difficulty=peaceful",
            "spawn-monsters=false",
            "spawn-animals=false",
            "spawn-npcs=false",
            "allow-nether=true",
            "gamemode=creative",
            "force-gamemode=true",
            "motd=crop_climates stress",
            "",
        ]), encoding="utf-8")
    (CLIENT / "options.txt").write_text("\n".join([
        "onboardAccessibility:false",
        "skipMultiplayerWarning:true",
        "joinedFirstServer:true",
        "tutorialStep:none",
        "pauseOnLostFocus:false",
        "enableVsync:false",
        "maxFps:260",
        "renderDistance:12",
        "simulationDistance:10",
        "narrator:0",
        "soundCategory_master:0.0",
        "fullscreen:false",
        "",
    ]), encoding="utf-8")
    print("ready:", SERVER, CLIENT)


if __name__ == "__main__":
    main()
