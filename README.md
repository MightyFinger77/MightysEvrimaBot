# EvrimaServerBot

Self-hosted **Discord** companion for **The Isle Evrima** dedicated servers: Steam↔Discord linking, **RCON** admin actions, basic economy, and parking-slot metadata (no proprietary game hooks).

---

## Table of contents

1. [Will Java work?](#will-java-work)
2. [What you need](#what-you-need)
3. [How it fits together](#how-it-fits-together)
4. [Discord application setup](#discord-application-setup)
5. [Evrima server (RCON)](#evrima-server-rcon)
6. [Configuration](#configuration)
7. [Build](#build)
8. [Run](#run)
9. [Slash commands reference](#slash-commands-reference)
10. [Troubleshooting](#troubleshooting)
11. [Security notes](#security-notes)

---

## Will Java work?

**Yes.** This bot uses:

- **[JDA](https://github.com/discord-jda/JDA)** (Discord’s official gateway/API for bots)
- **Java 17+** (LTS, same era as many server hosts)
- A small **SQLite** file for persistence
- An **Evrima binary RCON** TCP client (same framing as [TheIsle_RCON.py](https://github.com/modernham/The-Isle-Evrima-Server-Tools) / [evrima-rcon](https://github.com/theislemanager/evrima-rcon), not Source/mcrcon)

Thousands of production Discord bots run on Java. You are not depending on Minecraft or Spigot here—only the JVM and Maven dependencies listed in `pom.xml`.

**Requirements on the machine that runs the bot:**

| Requirement | Notes |
|-------------|--------|
| **Java 17+** | `java -version` should show 17 or newer. |
| **Network** | Outbound HTTPS to Discord; TCP to your Evrima host’s **RCON port** (often same machine → `127.0.0.1`). |
| **Disk** | SQLite file grows slowly (links, audit, economy, parking rows). |

---

## What you need

- A **Discord server** where you can manage roles and invite bots.
- A **Discord Application** + **Bot** user (see below).
- An **Evrima dedicated server** with **RCON enabled** and a known password/port.
- (Optional) A fixed **guild ID** so slash commands update in seconds during setup instead of using global registration delay.

---

## How it fits together

```
[ Discord users ]  <--->  [ This JVM process: JDA + SQLite ]
                               |
                               +-- TCP RCON ---> [ Evrima dedicated server ]
```

- **Discord** delivers slash commands and DMs.
- **RCON** sends text commands the game server understands (`announce`, `kick`, etc.).
- **SQLite** stores links, points, audit rows, and optional “parking” metadata you define later.

---

## Discord application setup

### 1. Create the application

1. Open the [Discord Developer Portal](https://discord.com/developers/applications).
2. **New Application** → name it (e.g. `My Isle Bot`).
3. Open the **Bot** tab → **Add Bot**.
4. Under **Token**, copy the token (keep it secret). Prefer setting env var `DISCORD_TOKEN` instead of pasting into files on shared machines.

### 2. Enable intents

On the **Bot** tab, under **Privileged Gateway Intents**, enable:

- **Server Members Intent** — used to resolve members for `whois`, timeouts, and role checks.

Without this, some commands may fail or see incomplete member data.

### 3. Invite URL

In **OAuth2 → URL Generator**:

- **Scopes:** `bot`, `applications.commands`
- **Bot permissions (minimum starting point):**
  - View channels, Send messages, Use slash commands  
  - **Moderate Members** (for `/evrima-mod timeout`)  
  - Read messages / history if you later add prefix commands (not required for slash-only)

Open the generated URL, pick your server, authorize.

### 4. Role IDs for staff

The bot does **not** use Discord’s permission system for RCON—it uses **role IDs** you list in `config.yml`:

- `roles.moderator` — `/evrima-mod …`
- `roles.admin` — `/evrima-admin …` (RCON + `/evrima-admin give` for points)
- `roles.head_admin` — same as admin for `/evrima-admin`, plus `/evrima-head …` only for this tier

**How to get a role ID:** Discord → Server Settings → Roles → right‑click **the role** → Copy Role ID (Developer Mode on). These are **role** snowflakes, not your personal Discord user ID.

Use YAML lists, for example:

```yaml
roles:
  moderator: [ 1234567890123456789 ]
  admin: [ 9876543210987654321 ]
  head_admin: [ 1111111111111111111 ]
```

---

## Evrima server (RCON)

Enable RCON in your dedicated server config (hosting docs usually mention `Game.ini`: `bRconEnabled=true`, password, port). The bot’s `rcon` section must match **host**, **port**, and **password**.

**Exact RCON command syntax** can vary slightly by game build. If something fails, compare the strings the bot sends (see `BotListener`) with your host’s RCON reference and adjust.

---

## Configuration

1. Copy `config.example.yml` to **`config.yml`** in the directory you will run the JAR from (or pass an explicit path; see [Run](#run)).
2. Fill in at least:
   - `discord.token` **or** environment variable `DISCORD_TOKEN`
   - `discord.guild_id` (recommended: your server’s ID) or `0` for global commands only
   - `discord.roles.*` with real role IDs
   - `rcon.host`, `rcon.port`, `rcon.password`

Paths like `database.path` are relative to the **process working directory** unless you use an absolute path.

**Ecosystem / population:** optional `population_dashboard.channel_id` (text channel) + `interval_minutes` posts one embed and **edits** it on a schedule; the message ID is stored in SQLite (`bot_kv`). For `/evrima ecosystem dashboard`, adjust `ecosystem.cache_ttl_seconds`, `ecosystem.title`, and optional `ecosystem.taxonomy_path` (YAML matching bundled `species-taxonomy.yml`) if your RCON `playerlist` lines use different species names. The parser also handles **one-line / pipe-separated** lists, counts **SteamID64** occurrences, phrases like **“119 players”**, and a **greedy species scan** when the server does not send one player per line.

**Scheduled corpse wipes:** set `scheduled_wipecorpses.interval_minutes` to a positive number to run RCON `wipecorpses` on that interval (same as `/evrima-admin wipecorpses`). Use `0` to disable. Optional `warn_before_minutes` (default 5) sends an in-game `announce` first; set `0` to skip. `announce_message` customizes the text. `interval_minutes` must be **greater than** `warn_before_minutes` for the warning to run. The schedule is counted from **when the JVM starts**; it is **not** saved to disk, so each bot **restart** resets the timer. With e.g. `interval_minutes: 120` and `warn_before_minutes: 5`, the **first** announce is about **115 minutes** after startup, then the wipe **5 minutes** later, then the pattern repeats. Failures are logged only (no Discord post).

---

## Build

From `EvrimaServerBot/1.0.0`:

```bash
mvn -q package
```

Fat JAR output:

```text
target/evrima-server-bot-1.0.0.jar
```

---

## Run

**Working directory:** Use the folder that contains `config.yml` (or pass the config path as the first argument).

```bash
cd path\to\bot-folder
java -jar target\evrima-server-bot-1.0.0.jar
```

**JDK 24+:** If you see warnings about `java.lang.System::load` / SQLite, either use **`start-bot.bat`** (it auto-adds `--enable-native-access=ALL-UNNAMED` when your `java` supports it) or run:

```bash
java --enable-native-access=ALL-UNNAMED -jar evrima-server-bot-1.0.0.jar
```

With an explicit config path:

```bash
java -jar evrima-server-bot-1.0.0.jar D:\configs\evrima-bot-config.yml
```

On first start the bot will:

1. Create/migrate the SQLite file (see `database.path`).
2. Log in to Discord.
3. Register slash commands (guild commands if `guild_id` is set and valid; otherwise **global** commands—Discord can take up to about an hour to show new global commands).

**Tip:** Set `discord.guild_id` to your Discord server’s numeric ID so commands register **on that guild** and usually appear **within seconds** after restart. With `guild_id: 0`, the log line *“Registered 1 global slash commands”* is expected; wait or switch to a guild ID.

You should see a log line like: `EvrimaServerBot logged in as …`

---

## Slash commands reference

Commands are split into **four roots** so you can hide staff trees in **Server Settings → Integrations → [bot] → Manage** (Command Permissions v2). The bot still **checks** `config.yml` role IDs at runtime.

### `/evrima` (everyone)

| Group | Subcommand | What it does |
|-------|------------|----------------|
| **link** | `start` | DMs a short code to finish linking. |
| **link** | `complete` | `code` + `steam_id` (SteamID64) → stores link. |
| **account** | `show` | Linked SteamID64. |
| **account** | `debug` | Your role IDs vs config (use if admin commands deny you). |
| **eco** | `balance` | Points balance. |
| **eco** | `spin` | Once per UTC day; random points (`economy` in config). |
| **dino** | `park` / `list` / `delete` / `retrieve` | Parking metadata (retrieve = placeholder). |
| **ecosystem** | `dashboard` | RCON `playerlist` → species counts when the text **includes** species-like tokens; some builds only return **Steam IDs + player names** (no dino column) — then only player totals work, not species %. Optional `fresh` bypasses cache. |

### `/evrima-mod` (configure visibility + bot checks `moderator` / `admin` / `head_admin`)

| Subcommand | What it does |
|------------|----------------|
| `whois` | Linked Steam + optional `getplayerdata` via RCON. |
| `timeout` | Discord timeout (minutes). |

### `/evrima-admin` (configure visibility + bot checks `admin` **or** `head_admin`)

| Subcommand | What it does |
|------------|----------------|
| `announce` | In-game broadcast. |
| `playerlist` | Raw connected-player text from RCON. |
| `kick` / `ban` / `dm` | **`kick`** uses a **space** after SteamID (same as older bot builds). **`ban`** / **`dm`** use **comma**-separated fields (`EvrimaRcon.lineBan` / `lineDirectMessage`, host-panel style). |
| `getplayer` | RCON `getplayerdata` for a SteamID64. |
| `wipecorpses` | Corpse / body cleanup (not deleting live AI). |
| `save` | RCON save. |
| `unlink` | Remove this bot’s stored Discord↔Steam link (not in-game). |
| `give` | Add points in the bot’s SQLite economy (not RCON). |
| `ai-toggle` | RCON `toggleai` — **flips** global AI On↔Off; does not target a species or delete dinos. |
| `ai-density` | RCON `aidensity` — spawn multiplier; `0` usually stops **new** spawns only. |
| `ai-classes` | RCON `disableaiclasses` — **blocks** AI types (e.g. `boar`). **Not a toggle**; no “enable” in this bot. |
| `ai-stop-spawns` | RCON **`aidensity 0`** only — slows/stops **new** AI spawns. Does **not** kill live AI, wipe corpses, or toggle AI. |
| `ai-wipe` | **Informational only** — explains that **The Isle Evrima**’s documented **binary RCON** verbs (`wipecorpses`, `toggleai`, `aidensity`, …) **do not** include clearing **living** wild AI; use **Insert → Admin → Wipe AI** in-game. Does **not** send RCON `custom` / free-text execs. |
| `ai-learning` | RCON `toggleailearning` if your build supports it. |

#### AI commands (quick reference)

| If you want… | Use… | Notes |
|--------------|------|--------|
| Stop **new** AI spawns | `/evrima-admin ai-stop-spawns` or `ai-density` → `0` | Does not remove creatures already in the world. |
| **Kill / clear living AI** | **Insert → Admin → Wipe AI** in Evrima | **`/evrima-admin ai-wipe`** only explains why RCON can’t do this; see that reply. |
| Turn AI spawning **off/on globally** | `/evrima-admin ai-toggle` | Each call **toggles**. |
| Stop **one AI type** from spawning | `/evrima-admin ai-classes` | **`disableaiclasses` is disable-only**; no matching “enable” RCON in this bot. |
| Clean **corpses** | `/evrima-admin wipecorpses` | Not live AI. |

**Note:** `ai-wipe` does **not** run RCON — it documents the **Evrima RCON** surface. Use **`ai-stop-spawns`** / **`ai-density`** or **`ai-toggle`** for spawn / master-switch behavior.

### `/evrima-head` (configure visibility + bot checks `head_admin` only)

| Subcommand | What it does |
|------------|----------------|
| `check` | Confirms head tier; placeholder for future tools. |

---

## Troubleshooting

| Symptom | Things to check |
|---------|------------------|
| **Bot offline / login fails** | Token correct? `DISCORD_TOKEN` vs `config.yml`. Bot not disabled in portal. |
| **Slash commands missing** | If `guild_id` is `0`, wait for global propagation (often up to ~1 hour) or set real guild ID and restart. Re-invite with `applications.commands` scope. |
| **`restricted method` / SQLite `System::load` warnings (JDK 24+)** | Use `start-bot.bat`, or add `--enable-native-access=ALL-UNNAMED` before `-jar`. Harmless if ignored today; future JDKs may require the flag. |
| **`10062` / “The application did not respond”** | Discord allows **3 seconds** to acknowledge a slash command. RCON often takes longer. The bot now **defers** (`…is thinking…`) for `/evrima-admin`, `/evrima-mod` (whois/timeout), `/evrima-admin give`, and `/evrima link start`. If it still happens, ensure **only one** JVM/process uses this bot token. |
| **Duplicate slash commands** | Usually **global + guild** both registered, or an old `/evrima` tree plus the new split roots. **Restart the latest JAR** with `discord.guild_id` set: it **clears globals first**, then registers on your guild **synchronously**. For `guild_id: 0`, it clears this bot’s commands on up to 25 guilds before registering globals. Remove leftovers in [Developer Portal](https://discord.com/developers/applications) → **Commands** if needed. |
| **“Need admin role” but `head_admin` is set** | Listing an ID in `config.yml` does **not** grant it — your Discord account must **have that role** in the server. Run `/evrima account debug` and compare your role IDs to the config. **Restart** the bot after editing `config.yml`. |
| **`Missing Access` or intent errors** | Server Members Intent enabled on the Bot tab. |
| **RCON errors / timeouts** | Firewall, correct port, password, `bRconEnabled`. Bot host can reach game host (try `telnet`/`Test-NetConnection`). |
| **“Bot is thinking…” for a long time** | Normal for RCON-heavy commands: the bot **defers** the reply (Discord’s 3s limit), then waits for **TCP connect + auth + server work** for each RCON call. Slow network, busy game server, or a high `rcon.timeout_ms` (waiting on reads) all add delay. Not a Discord “cache” issue. |
| **Kick/ban/DM odd behavior** | **`kick`** = `kick <SteamID> <reason>` (space). **`ban`** / **`dm`** = commas (`lineBan` / `lineDirectMessage`); commas inside text → **`;`**. Ban display name **`Unknown`** unless you change `BotListener`. To use **`lineKick`** (comma after SteamID), swap one line in `BotListener`. |
| **DM link fails** | User must allow DMs from server members; user not blocking the bot. |
| **Timeout fails** | Bot role must be **above** the target’s top role; bot has **Moderate Members**. |

---

## Security notes

- **Never commit** `config.yml` with a live token (this repo’s `.gitignore` ignores it).
- Prefer **`DISCORD_TOKEN`** on the host over a token in a file.
- RCON password is effectively **root on the game server**—treat like a secret.
- Anyone with an **admin role ID** in config can run destructive RCON; keep those roles tight.

---

## License / attribution

Independent project; not affiliated with PrimalCore or The Isle. Extend and run on your own infrastructure.
