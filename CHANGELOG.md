# Changelog

## 1.0.1

- Improved Discord channel-topic update behavior to reduce 429 rate-limit spam on multi-channel setups.
- Admin commands now support player name targeting (not only SteamID64) for moderation actions.
- Added dynamic species population control with runtime toggles and live cap editing from slash commands.
- Added runtime control for scheduled corpse wipes (enable/disable/dynamic + live setting edits, persisted).
- Added dynamic corpse-wipe threshold mode with anti-flap grace handling in both directions.
- Cleaned up ecosystem dashboard embed text for clearer status output.
- Updated config examples and README/wiki docs to match all new runtime controls and options.
