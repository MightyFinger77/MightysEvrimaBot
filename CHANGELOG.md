# Changelog

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
