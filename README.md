# EvrimaServerBot

Self-hosted **Discord** companion for **The Isle Evrima** dedicated servers: Steam↔Discord linking, **RCON** admin actions, basic economy, and parking-slot metadata (no proprietary game hooks).

---

## Table of contents

1. [What you need](#what-you-need)
2. [How it fits together](#how-it-fits-together)
3. [Discord application setup](#discord-application-setup)
4. [Evrima server (RCON)](#evrima-server-rcon)
5. [Configuration](#configuration)
6. [Build](#build)
7. [Run](#run)
8. [Slash commands reference](#slash-commands-reference)
9. [Troubleshooting](#troubleshooting)
10. [Security notes](#security-notes)
11. [License / attribution](#license--attribution)
12. [Roadmap / todo](#roadmap--todo)

---

## What you need

- A **Discord server** where you can manage roles and invite bots.
- A **Discord Application** + **Bot** user (see below).
- An **Evrima dedicated server** with **RCON enabled** and a known password/port.
- A machine to run the bot: **Java 17+** (JDK for [building](#build); JRE is enough to run the shaded JAR).
- (Optional) A fixed **guild ID** so slash commands update in seconds during setup instead of using global registration delay.

---

## How it fits together

```
[ Discord users ]  <--->  [ This JVM process: JDA + SQLite ]
                               |
                               +-- TCP RCON ---> [ Evrima dedicated server ]
```

- **Discord** delivers slash commands and DMs. (DMs currently don't work, this is an Isle issue not an EvrimaBot issue)
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

The bot does **not** use Discord’s permission system for RCON—it uses **role IDs** you list in your config file (usually **`configs/config.yml`**):

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

1. **Default layout:** run the JAR with **no arguments** from your bot folder. It looks for an existing **`configs/config.yml`** first, then **`config/config.yml`** (singular folder). If neither exists, it creates **`configs/`** and writes only **missing** files: **`config.yml`** (copied from the bundled template in the JAR) and **`species-taxonomy.yml`**. It never overwrites existing YAMLs. Edit your config and set token/RCON/etc. You can pass a custom path as the first argument (see [Run](#run)). If a mistaken first run created an empty **`configs/`** with defaults while your real files are under **`config/`**, delete the **`configs/`** folder (or the stray files inside it) so the bot picks **`config/`**.
2. Edit **`config.yml`** (after first-run extraction, typically **`configs/config.yml`**) and fill in at least:
   - `discord.token` **or** environment variable `DISCORD_TOKEN`
   - `discord.guild_id` (recommended: your server’s ID) or `0` for global commands only
   - `discord.roles.*` with real role IDs
   - `rcon.host`, `rcon.port`, `rcon.password`

Paths like `database.path` are relative to the **process working directory** (the folder you `cd` into before `java -jar`) unless you use an absolute path.

**Ecosystem / population:** optional `population_dashboard.channel_id` (text channel) + `interval_minutes` posts one embed and **edits** it on a schedule; the message ID is stored in SQLite (`bot_kv`). For `/evrima ecosystem dashboard`, adjust `ecosystem.cache_ttl_seconds` and `ecosystem.title`. **Species matching** always uses **`species-taxonomy.yml` in the same directory as `config.yml`** (e.g. `configs/species-taxonomy.yml`); the bot extracts a default from the JAR if that file is missing. The parser also handles **one-line / pipe-separated** lists, counts **SteamID64** occurrences, phrases like **“119 players”**, and a **greedy species scan** when the server does not send one player per line.

**Adaptive AI density:** optional `adaptive_ai_density` runs RCON `aidensity` on a timer from **how full the server is** vs `max_players` (same **player estimate** as the ecosystem dashboard). Set `enabled: true` and `max_players` to your slot cap. **`tiers`** is a list of `{ min_percent, max_percent, density }` bands (inclusive, 0–100); gaps with no matching band are skipped (with a warning). Empty / omitted `tiers` uses the same four default bands as the bundled `config.yml` (0–24 → `2.0`, 25–49 → `1.0`, 50–79 → `0.75`, 80–100 → `0.5`). The bot stores the last applied value in SQLite so it **does not** re-send RCON when the target density is unchanged. Manual `/evrima-admin ai-density` still works; the next scheduler tick may override it.

**Species population control (dynamic dino locks):** optional `species_population_control` edits RCON `updateplayables` from live ecosystem counts so capped species can be temporarily removed/re-added without manual admin intervention. Rule is **lock at** `count >= cap`, **unlock at** `count <= cap - unlock_below_offset` (hysteresis to reduce flapping). Tick interval is `interval_seconds` (default 60). On `updateplayables` failure, the bot retries once after **1 second**. Optional `announce_changes` can send in-game notices (default false to avoid spam). Admins can toggle enabled state and caps with `/evrima-admin species-control`, `species-cap-set`, and `species-cap-clear` — changes are **written to `config.yml`** (same idea as a Minecraft plugin; not stored in SQLite).

**Channel topic (server status line):** optional `server_status_topic` sets **Discord channel topic(s)** on a timer (e.g. `12/60 players online | 847 unique players seen | Last update: …`). Discord **heavily rate-limits** `PATCH /channels` (guild **shared** bucket); the bot only calls the API when **player count** or **`max_players`** (or bridge-uptime minute, if enabled) **change** — not when only “Last update” or **unique-seen** would change, so new Steam IDs in `playerlist` do not trigger a topic refresh by themselves (the **unique** line still updates the next time the fingerprint changes, e.g. population moves). With **two or more** channels, topic PATCHes run **one at a time**, then wait **`multi_channel_stagger_seconds`** (default **210**, clamped 120–900) before the next — Discord has hit **~183s** Retry-After on this route; raise stagger or use fewer channels if you still see 429s. Prefer **`interval_minutes` ≥ 5** for several `channel_ids`; keep **`show_bridge_uptime: false`** unless needed. Use **`channel_id`** and/or **`channel_ids`** (merged, deduplicated). **Manage Channels** is required. **Unique players** in the topic text come from SQLite (Steam IDs seen while the bot runs). Use `max_players` for the `/` cap, or `0` to omit it.

**Scheduled corpse wipes:** configure `scheduled_wipecorpses.enabled` + `interval_minutes` to run RCON `wipecorpses` automatically (same action as `/evrima-admin wipecorpses`). `enabled` supports `true`, `false`, and `dynamic`. In `dynamic`, wipes target ON when online percent is at least `dynamic_enable_percent` of `dynamic_max_players`, and OFF when below it; transitions are debounced by `dynamic_disable_grace_seconds` in both directions (anti-flap). After a **pre-wipe `announce`** succeeds, that wipe still runs on schedule even if population drops below threshold (players were warned). Optional `warn_before_minutes` sends an in-game `announce` first; set `0` to skip. `announce_message` customizes the warning text. `/evrima-admin corpse-wipe-control`, `corpse-wipe-set`, and `corpse-wipe-clear` **update `config.yml`**; `corpse-wipe-clear` resets fields (or the whole section) to values from the bundled default template (`config.yml` in the JAR).

**In-game log → Discord (chat + kills/deaths):** Evrima RCON does **not** expose chat or kill feeds. Optional `ingame_chat_log` tails **`TheIsle.log`** and posts lines that match **any** substring in `line_contains` (YAML list or one string). Defaults include **`LogTheIsleChatData`** (chat) and **`LogTheIsleKillData`** (kills / many death messages such as PvP — same markers as [Theislemanager/Chatbot](https://github.com/Theislemanager/Chatbot)). By default **`mirror_local_chat: false`** drops **`[Spatial]`** / **`[Local]`** lines after parse so only global-style traffic is mirrored; set **`mirror_local_chat: true`** to include proximity chat. The bot **parses** known formats into short Discord lines, e.g. `[Global] **Player:** message` and `**Kill:** **Player** — …`. Other shapes get a best-effort strip of tags/SteamIDs. Starvation / odd kills: add substrings from your log if needed. **YAML on Windows:** use forward slashes or single-quoted paths (see `ingame_chat_log` in the default `config.yml` bundled with the JAR). `channel_id: 0` disables; offset in `bot_kv`; new paths start at EOF.

---

## Build

Build the bot yourself if you are not using a prebuilt release JAR. You only need this once per version you want to run.

### Prerequisites

- **Java Development Kit (JDK) 17** or newer (LTS such as Temurin 17/21 is fine). The project targets **Java 17** (`release 17` in `pom.xml`).
- **Apache Maven 3.8+** (3.9.x recommended) on your `PATH`.
- A copy of this project’s source (git clone, zip download, etc.). Open a terminal in the **project root** — the directory that contains **`pom.xml`** (for this repo that is usually a versioned folder like `EvrimaServerBot/1.0.1`).

Check versions:

```bash
java -version
mvn -version
```

`java -version` should report **17** or higher. `mvn -version` should show Java 17+ as the runtime Maven uses.

### Produce the runnable (“fat”) JAR

The **`maven-shade-plugin`** packages dependencies into a **single executable JAR**. That is what you must run — **not** the small `original-*.jar` or a classpath-only build.

From the project root (`pom.xml` here):

```bash
mvn clean package
```

- Omit **`clean`** if you only changed a few files and want a faster rebuild: `mvn package`.
- Add **`-DskipTests`** if the project ever has tests and you need to skip them: `mvn -q -DskipTests package` (`-q` is optional quiet output).

### Output

After a successful build:

| Artifact | Location | Notes |
|----------|----------|--------|
| **Runnable bot JAR** | `target/evrima-server-bot-1.0.1.jar` | **Use this** with `java -jar`. Includes dependencies (tens of MB — if the file is only a few hundred KB, you did not build the shaded artifact). |
| Original (non-shaded) | `target/original-evrima-server-bot-1.0.1.jar` | Internal; **do not** run this as the bot — it is not a fat JAR. |

Copy **`evrima-server-bot-1.0.1.jar`** (the one **without** `original-` in the name) to the folder where you run the bot. Optionally add **`start-bot.bat`** (Windows) or **`start-bot.sh`** (Linux/macOS), then follow [Run](#run).

### Windows quick reference

```powershell
cd C:\path\to\EvrimaServerBot\1.0.1
mvn clean package
dir target\evrima-server-bot-1.0.1.jar
```

### Linux / macOS quick reference

```bash
cd /path/to/EvrimaServerBot/1.0.1
mvn clean package
ls -lh target/evrima-server-bot-1.0.1.jar
```

### IDE (IntelliJ, VS Code, Eclipse)

Import the folder as a **Maven** project (open `pom.xml` or the root directory). Use the IDE’s Maven panel to run the **`package`** lifecycle, or execute the same `mvn clean package` in a terminal with the project root as the working directory.

### Common build issues

- **`mvn` not found** — Install Maven and add it to `PATH`, or use a full path to `mvn`.
- **Wrong Java** — If Maven uses Java 8/11, set **`JAVA_HOME`** to a JDK 17+ install and restart the terminal.
- **`BUILD FAILURE` / compiler errors** — Ensure you are on the intended **branch or version folder** and JDK **17+**.
- **JAR runs but classes are missing** — You started **`original-*.jar`** or a non-shaded artifact; run **`target/evrima-server-bot-1.0.1.jar`** from a **`package`** build that completed **`shade`**.

---

## Run

**Working directory:** Run from the folder where you want **`configs/`** (or **`config/`**) and **`data/`** to live. With **no arguments**, the bot resolves **`configs/config.yml`** or **`config/config.yml`** (see [Configuration](#configuration)). To use a config somewhere else, pass the **full path to `config.yml`** as the first argument.

**Helper scripts** (same folder as the shaded JAR): **Windows** → `start-bot.bat`; **Linux / macOS** → `start-bot.sh` (`chmod +x start-bot.sh` once, then `./start-bot.sh`). Both check that the fat JAR exists, warn if the file is too small, and add `--enable-native-access=ALL-UNNAMED` when your JDK supports it.

```bash
cd /path/to/bot-folder
java -jar evrima-server-bot-1.0.1.jar
```

```powershell
cd C:\path\to\bot-folder
java -jar evrima-server-bot-1.0.1.jar
```

**JDK 24+:** If you see warnings about `java.lang.System::load` / SQLite, either use **`start-bot.bat`** / **`start-bot.sh`** (they add `--enable-native-access=ALL-UNNAMED` when supported) or run:

```bash
java --enable-native-access=ALL-UNNAMED -jar evrima-server-bot-1.0.1.jar
```

With an explicit **`config.yml`** path (taxonomy file is expected **in the same folder**; missing **`species-taxonomy.yml`** is created from the JAR if absent):

```bash
java -jar evrima-server-bot-1.0.1.jar /opt/evrima-bot/configs/config.yml
```

### On first start (and every start)

Order matters for troubleshooting logs.

1. **Config path** — With no CLI args: use existing **`configs/config.yml`** or **`config/config.yml`**, or create **`configs/`** and write **missing** defaults (**`config.yml`** from the bundled template in the JAR, **`species-taxonomy.yml`** from the JAR). Existing files are never overwritten. With a CLI path: that file is used; **`species-taxonomy.yml`** is still ensured **next to** that `config.yml` if missing.
2. **Load `config.yml`** — Validates token (env or file), RCON password, etc. Fix errors and restart if startup throws.
3. **SQLite** — Creates or migrates the database file (see `database.path`, usually under **`data/`** relative to the working directory).
4. **Discord** — Connects and caches members (Server Members intent).
5. **Slash commands** — Registers command **roots** on the guild when **`discord.guild_id`** is set and the bot is in that server; otherwise registers **globally** (Discord can take up to about an hour for new global commands to appear everywhere).
6. **Background tasks** — Starts schedulers you enabled in config (population dashboard embed, channel topics, in-game log tail, adaptive AI density, species population control, scheduled corpse wipes, etc.).

**Tip:** Set `discord.guild_id` to your Discord server’s numeric ID so commands register **on that guild** and usually appear **within seconds** after restart. With `guild_id: 0`, expect a log line like **`Registered N global slash command roots`** (N is the number of top-level slash roots, e.g. `/evrima`, `/evrima-admin`, …); global propagation can be slow—switch to a real guild ID for faster iteration.

You should see: **`EvrimaServerBot logged in as …`** (and earlier, **`Config directory: …`** when using the default no-args layout).

---

## Slash commands reference

Commands are split into **four roots** so you can hide staff trees in **Server Settings → Integrations → [bot] → Manage** (Command Permissions v2). The bot still **checks** role IDs from your config (e.g. **`configs/config.yml`**) at runtime.

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
| `whois` | Linked Steam + filtered `getplayerdata` via RCON. Use **`user`** (Discord) **or** **`player`** (SteamID64 / in-game name from live `playerlist`), not both. |
| `timeout` | Discord timeout (minutes). |

### `/evrima-admin` (configure visibility + bot checks `admin` **or** `head_admin`)

**Config persistence:** If a subcommand changes a setting that lives in `config.yml`, the bot **updates that file** and reloads config in memory (`applyYamlMutation` in `BotListener`). **`/evrima-admin reload`** re-reads `config.yml` and `species-taxonomy.yml` from disk after **manual** edits (no JVM restart). Species and corpse-wipe slash edits **only change the relevant lines** under `species_population_control` / `scheduled_wipecorpses` so your banner, comments, and the rest of the file layout stay intact (no full-file YAML re-dump). Other entries here are **RCON** (game server) or **SQLite** (e.g. `/evrima-admin give` points). `bot_kv` is still used for operational state such as chat-log tail offsets and dashboard message IDs, not for YAML tuning.

| Subcommand | What it does |
|------------|----------------|
| `announce` | In-game broadcast. |
| `playerlist` | Raw connected-player text from RCON. |
| `kick` / `ban` / `dm` | Option **`player`**: **SteamID64** or **in-game display name** (bot fetches `playerlist` and matches; ambiguous names get a list). **`kick`** uses a **space** after the resolved SteamID. **`ban`** / **`dm`** use **comma** fields on the wire (`lineBan` / `lineDirectMessage`). |
| `getplayer` | RCON `getplayerdata`; **`player`** = SteamID64 or name (same resolver as `dm`). |
| `wipecorpses` | Corpse / body cleanup (not deleting live AI). |
| `reload` | Reload **`config.yml`** + **`species-taxonomy.yml`** from disk into memory (manual YAML edits). Does not restart the process — scheduler **intervals** and some **channel/log paths** may still need a **full restart** to apply everywhere. |
| `save` | RCON save. |
| `unlink` | Remove this bot’s stored Discord↔Steam link (not in-game). |
| `give` | Add points in the bot’s SQLite economy (not RCON). |
| `ai-toggle` | RCON `toggleai` — **flips** global AI On↔Off; does not target a species or delete dinos. |
| `ai-density` | RCON `aidensity` — spawn multiplier; `0` usually stops **new** spawns only. |
| `ai-classes` | RCON `disableaiclasses` — **blocks** AI types (e.g. `boar`). **Not a toggle**; no “enable” in this bot. |
| `ai-stop-spawns` | RCON **`aidensity 0`** only — slows/stops **new** AI spawns. Does **not** kill live AI, wipe corpses, or toggle AI. |
| `ai-wipe` | **Informational only** — explains that **The Isle Evrima**’s documented **binary RCON** verbs (`wipecorpses`, `toggleai`, `aidensity`, …) **do not** include clearing **living** wild AI; use **Insert → Admin → Wipe AI** in-game. Does **not** send RCON `custom` / free-text execs. |
| `ai-learning` | RCON `toggleailearning` if your build supports it. |
| `species-control` | `mode=on|off|status` — toggle `species_population_control.enabled` or show **status** (values from `config.yml`). |
| `species-cap-set` | Set `species_population_control.caps.<species>` in `config.yml` (`species` + `cap`; `0` = unlimited / unmanaged). **`species` must match a name in the bundled default `caps` roster** (case-insensitive); unknown names are rejected with no file change. On-disk key casing is preserved when you edit an existing line. |
| `species-cap-clear` | Reset one species cap to the bundled default (or **0**). Same **bundled roster** validation as `species-cap-set`. |
| `species-cap-list` | List capped species from loaded config. |
| `corpse-wipe-control` | Toggle/status for `scheduled_wipecorpses.enabled` (`on|off|dynamic|status`); writes `config.yml`. |
| `corpse-wipe-set` | Set a `scheduled_wipecorpses` field in `config.yml` by `key` and `value`. |
| `corpse-wipe-clear` | Reset one field (or `all`) under `scheduled_wipecorpses` to bundled defaults (same as `config.yml` shipped in the JAR). |

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
| **Bot offline / login fails** | Token correct? `DISCORD_TOKEN` vs `configs/config.yml` (or your chosen path). Bot not disabled in portal. |
| **Slash commands missing** | If `guild_id` is `0`, wait for global propagation (often up to ~1 hour) or set real guild ID and restart. Re-invite with `applications.commands` scope. |
| **`restricted method` / SQLite `System::load` warnings (JDK 24+)** | Use `start-bot.bat` / `start-bot.sh`, or add `--enable-native-access=ALL-UNNAMED` before `-jar`. Harmless if ignored today; future JDKs may require the flag. |
| **`10062` / “The application did not respond”** | Discord allows **3 seconds** to acknowledge a slash command. RCON often takes longer. The bot now **defers** (`…is thinking…`) for `/evrima-admin`, `/evrima-mod` (whois/timeout), `/evrima-admin give`, and `/evrima link start`. If it still happens, ensure **only one** JVM/process uses this bot token. |
| **Duplicate slash commands** | Usually **global + guild** both registered, or an old `/evrima` tree plus the new split roots. **Restart the latest JAR** with `discord.guild_id` set: it **clears globals first**, then registers on your guild **synchronously**. For `guild_id: 0`, it clears this bot’s commands on up to 25 guilds before registering globals. Remove leftovers in [Developer Portal](https://discord.com/developers/applications) → **Commands** if needed. |
| **“Need admin role” but `head_admin` is set** | Listing an ID in `config.yml` does **not** grant it — your Discord account must **have that role** in the server. Run `/evrima account debug` and compare your role IDs to the config. **Restart** the bot after editing `config.yml`. |
| **`Missing Access` or intent errors** | Server Members Intent enabled on the Bot tab. |
| **RCON errors / timeouts** | Firewall, correct port, password, `bRconEnabled`. Bot host can reach game host (try `telnet`/`Test-NetConnection`). |
| **“Bot is thinking…” for a long time** | Normal for RCON-heavy commands: the bot **defers** the reply (Discord’s 3s limit), then waits for **TCP connect + auth + server work** for each RCON call. Slow network, busy game server, or a high `rcon.timeout_ms` (waiting on reads) all add delay. Not a Discord “cache” issue. |
| **Kick/ban/DM odd behavior** | **`kick`** = `kick <SteamID> <reason>` (space). **`ban`** / **`dm`** = commas (`lineBan` / `lineDirectMessage`); commas inside text → **`;`**. Ban display name **`Unknown`** unless you change `BotListener`. To use **`lineKick`** (comma after SteamID), swap one line in `BotListener`. |
| **DM link fails** | User must allow DMs from server members; user not blocking the bot. |
| **Timeout fails** | Bot role must be **above** the target’s top role; bot has **Moderate Members**. |
| **No in-game chat / kills in Discord** | `ingame_chat_log.path` must be the **live** `TheIsle.log`; bot must **read** it. Wrong `line_contains` markers = no posts — grep the log for chat/kill lines and add their substrings. Changing `path` resets tracking for that path on next start. |

---

## Security notes

- **Never commit** `config.yml` with a live token (this repo’s `.gitignore` ignores it).
- Prefer **`DISCORD_TOKEN`** on the host over a token in a file.
- RCON password is effectively **root on the game server**—treat like a secret.
- Anyone with an **admin role ID** in config can run destructive RCON; keep those roles tight.

---

## License / attribution

Independent project; not affiliated with The Isle. Extend and run on your own infrastructure.

---

## Roadmap / todo

- [ ] Make species population caps dynamic according to max_player percentage
- [ ] Complete dino parking feature
- [ ] Find a way to implement ai-wipe... (set density to 0 briefly maybe?)
- [ ] Implement MySQL/MariaDB
- [ ] Fix playerdata showing IP: (not present in getplayerdata text)
