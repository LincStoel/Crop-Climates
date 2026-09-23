# Stress harness

Dev-only tooling for measuring crop_climates under load. None of it ships: the
`crop_climates_stress` mod lives in the `stress` source set and is loaded only by
the `stressServer` / `stressClient` runs.

## Pieces

| Where | What |
|---|---|
| `src/stress/` | `crop_climates_stress` mod: `/ccstress` commands, forced ticking, scenario builders, per-tick timer, growth tracker, diagnostics counters (mixins into crop_climates) |
| `tools/stress/setup.py` | Prepares `run-stress/server` and `run-stress/client`: copies Cold Sweat/spark/Jade from `run/mods`, flat world, RCON on 127.0.0.1 with a random password, `eula=true` |
| `tools/stress/rcon.py` | Stdlib RCON client (`python tools/stress/rcon.py "<command>" ...`) |
| `tools/stress/harness.py` | Session helpers: timed windows, local spark profiles, summaries |
| `tools/stress/scenarios.py` | The scenarios (`python tools/stress/scenarios.py s1 s2 ...`) |
| `tools/stress/client_session.py` | Launches the stress client and flies StressBot through the built scenarios: FPS/particle CSV, screenshots, Alt tooltips |
| `tools/stress/collect.py` | Gathers every results folder into one `collected.json` |
| `tools/stress/advancement_check.py` | With the stress client, revokes and earns each advancement through the real code paths, thresholds included |
| `tools/stress/parking_check.py` | With the stress client, checks too-large parking, the size ceiling and the height clamp (`restart` mode after a server restart) |
| `tools/stress/SparkSummary.java` | Decodes `.sparkprofile` files locally with spark's own proto classes |

## Workflow

```
python tools/stress/setup.py            # once
./gradlew runStressServer               # headless dev server in run-stress/server
python tools/stress/scenarios.py s0 f1  # any scenarios, in order
./gradlew runStressClient               # optional: auto-joins as StressBot
python tools/stress/collect.py          # everything in run-stress/results -> collected.json
```

Set `STRESS_TAG` (for example `STRESS_TAG=-after`) to suffix every window label and
results folder, so a rerun on other code never overwrites the baseline.

Results land in `run-stress/results/<label>/`: `mspt.json` (per-tick percentiles and
counters), `ticks.csv`, and for profiled windows `profile.sparkprofile` plus
`profile.txt`/`profile.json` decoded locally. spark is only ever asked to
`--save-to-file`; nothing is uploaded.

## Notes

- Vanilla random-ticks only chunks within 128 blocks of a non-spectator player; the
  harness forces chunks with a NeoForge ticking ticket (`/ccstress ticket add`) so
  crops grow without anyone online.
- Vanilla RCON reads one packet per socket read; `rcon.py` sends one command at a
  time and reads until the reply ends.
- Growth-rate checks compare a control run (`growthFloor = growthMax = 1.0`, i.e.
  vanilla speed with the hooks still running) against the defaults.
