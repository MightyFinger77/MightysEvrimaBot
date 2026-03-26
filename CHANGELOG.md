# Changelog

## 1.2.0

- **Dino parking:** `INFO` console logs for park (including disk-only offline fallback when `playerdata_file` captures bytes), list, delete, and retrieve. Optional **`economy.parking_slots`**: default free slots, max cap, point cost curve (`base_price_per_slot × price_multiplier^purchased_extras`), **`/evrima eco parking`** and **`/evrima eco parking-buy`**. New DB table **`economy_extra_parking_slots`**. **`config_version: 4`** merges new YAML keys.
- **In-game log mirror:** **`LogTheIsleJoinData`** (and optional **`LogTheIsleLeaveData`**) lines are parsed into short Discord join/leave messages. Templates live in **`kill-flavor.yml`** as **`join_quips`** / **`leave_quips`** (same **`kill_flavor_enabled`** toggle as kills). Bundled **`config.yml`** defaults include `LogTheIsleJoinData` in **`line_contains`**. **`config_version`** in the bundled template is **4** (parking economy keys); older files may still show **3** until migration runs once.
- **Config migration:** `formatYamlValue` now writes maps as valid YAML flow objects (`{ key: value }`) instead of Java `Map.toString()` (`key=value`). The old output broke re-parsing of lists such as **`adaptive_ai_density.tiers`** after merge (startup `IOException` on tier validation). If your `config.yml` already has corrupted tier lines, replace the `tiers:` block with the bundled template or valid `{ min_percent: …, max_percent: …, density: … }` entries.
- **README:** new section **The Isle game build compatibility** — RCON, `TheIsle.log` parsing, dino parking / logout autosave strings, and `playerlist` / `getplayerdata` shapes **depend on the Evrima dedicated-server build**; defaults may need edits after a game update.
- **Bundled `config.yml`:** `dino_park` block reorganized (clearer comments and key order for `logout_autosave`); behavior unchanged for existing keys.
- **Dino parking:** removed `dino_park.playerdata_file.line_block_fallback_when_no_capture` and the text-merge restore path; on-disk restore is **byte capture at park + restore at retrieve** only. Existing configs with the old key are ignored on load.
- **Logout autosave:** removed `character_cleared_line_contains` and `defer_ttl_seconds` (legacy defer mode). `hard_disconnect_delay_seconds: 0` now uses a **300s** delay like the default instead of waiting for custom “character cleared” log lines.

## 1.0.2

- **Kill flavor (Discord log mirror):** optional themed lines for PvP, AI kills, natural deaths, and same-species fights via **`kill-flavor.yml`** next to `config.yml` (seeded from the JAR when missing). Toggle with **`ingame_chat_log.kill_flavor_enabled`**; optional **`kill_flavor_path`** for a custom filename. Placeholders: `{killer}`, `{victim}`, `{killerSpecies}`, `{victimSpecies}`, `{killerSex}`, `{victimGender}`, `{species}`, `{sex}`, `{cause}`. If flavor is off, the file is empty, or no template matches, posts use the **factual** kill line (no leading `Kill:` label).
- **`config_version: 2`** — bundled `config.yml` adds the new `ingame_chat_log` keys; migration merges them into existing configs on startup.
- **Species population control:** re-adds a capped species to RCON **`updateplayables`** when population is under the unlock threshold and the species is **missing** from the current list, even if this bot process never recorded a prior lock (fixes “stuck off” after restarts or missed lock ticks).
- **Console logging:** `INFO` lines for `/evrima-admin` AI density, AI classes, species control, and cap edits; adaptive AI density and species schedulers log explicit **(scheduler, automatic)** messages when they change RCON.

## 1.0.1

- First run with no CLI args: uses **`configs/config.yml`** if present, else **`config/config.yml`**, else creates **`configs/`** and writes only **missing** default YAMLs from the JAR (optional: pass custom config path as arg1).
- Improved Discord channel-topic update behavior to reduce 429 rate-limit spam on multi-channel setups.
- Admin commands now support player name targeting (not only SteamID64) for moderation actions.
- Added dynamic species population control with runtime toggles and live cap editing from slash commands.
- Added runtime control for scheduled corpse wipes (enable/disable/dynamic + live setting edits, persisted).
- Added dynamic corpse-wipe threshold mode with anti-flap grace handling in both directions.
- Cleaned up ecosystem dashboard embed text for clearer status output.
- Updated config examples and README/wiki docs to match all new runtime controls and options.
