package com.isle.evrima.bot.discord;

import com.isle.evrima.bot.config.BotConfig;
import com.isle.evrima.bot.config.EconomyParkingSlotsConfig;
import com.isle.evrima.bot.config.ConfigYamlUpdater;
import com.isle.evrima.bot.config.ConfigYamlUpdater.ScheduledWipeResetKey;
import com.isle.evrima.bot.config.LiveBotConfig;
import com.isle.evrima.bot.db.Database;
import com.isle.evrima.bot.dino.ParkedDinoPayload;
import com.isle.evrima.bot.dino.PlayerdataFileRestore;
import com.isle.evrima.bot.ecosystem.EcosystemEmbeds;
import com.isle.evrima.bot.ecosystem.PlayerlistPopulationParser;
import com.isle.evrima.bot.ecosystem.PlayerTargetResolver;
import com.isle.evrima.bot.ecosystem.PopulationDashboardService;
import com.isle.evrima.bot.ecosystem.SpeciesTaxonomy;
import com.isle.evrima.bot.rcon.EvrimaRcon;
import com.isle.evrima.bot.rcon.PlayerdataIpExtract;
import com.isle.evrima.bot.rcon.RconService;
import com.isle.evrima.bot.schedule.ScheduledCorpseWipeScheduler;
import com.isle.evrima.bot.schedule.SpeciesPopulationControlScheduler;
import com.isle.evrima.bot.security.PermissionService;
import com.isle.evrima.bot.security.StaffTier;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.time.Instant;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Discord slash entrypoint. <b>Config persistence:</b> subcommands that alter settings defined in {@code config.yml}
 * must use {@link #applyYamlMutation} with {@link com.isle.evrima.bot.config.ConfigYamlUpdater} — never {@code bot_kv}
 * for those values. RCON-only admin actions and economy/link data are unaffected. **`/evrima-admin reload`**
 * calls {@link #reloadYamlFromDisk()} (config + taxonomy + kill-flavor + auto-messages + scheduler hooks). When adding reload-sensitive
 * components, extend {@link #applyYamlMutation} or {@link #reloadYamlFromDisk()} as needed.
 */
public final class BotListener extends ListenerAdapter {

    private static final Logger LOG = LoggerFactory.getLogger(BotListener.class);
    private static final String ALPH = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private final LiveBotConfig live;
    private final Database database;
    private final RconService rcon;
    private final PermissionService permissions;
    private final PopulationDashboardService population;
    private final SpeciesPopulationControlScheduler speciesControl;
    private final ScheduledCorpseWipeScheduler corpseWipe;
    private final AutoMessageScheduler autoMessageScheduler;
    private final SecureRandom secureRandom = new SecureRandom();

    public BotListener(
            LiveBotConfig live,
            Database database,
            RconService rcon,
            PermissionService permissions,
            PopulationDashboardService population,
            SpeciesPopulationControlScheduler speciesControl,
            ScheduledCorpseWipeScheduler corpseWipe,
            AutoMessageScheduler autoMessageScheduler
    ) {
        this.live = live;
        this.database = database;
        this.rcon = rcon;
        this.permissions = permissions;
        this.population = population;
        this.speciesControl = speciesControl;
        this.corpseWipe = corpseWipe;
        this.autoMessageScheduler = autoMessageScheduler;
    }

    @Override
    public void onSlashCommandInteraction(@NotNull SlashCommandInteractionEvent event) {
        String root = event.getName();
        try {
            switch (root) {
                case "evrima" -> dispatchEvrima(event);
                case "evrima-mod" -> dispatchEvrimaMod(event);
                case "evrima-admin" -> dispatchEvrimaAdmin(event);
                case "evrima-head" -> dispatchEvrimaHead(event);
                default -> { /* ignore other apps */ }
            }
        } catch (SQLException e) {
            LOG.error("Database error", e);
            event.reply("Database error — check logs.").setEphemeral(true).queue();
        } catch (IllegalArgumentException | IllegalStateException e) {
            event.reply(truncate(e.getMessage(), 2000)).setEphemeral(true).queue();
        }
    }

    private static final String EVRIMA_PUBLIC_HELP = """
            **Public commands** (`/evrima`)
            
            **`/evrima help`** — this list
            
            **Link:** `link start` (DM code) → `link complete` with code + your **SteamID64**
            **Account:** `account show` · `account debug` (roles / troubleshooting)
            **Economy:** `eco balance` · `eco spin` (once per UTC day) · `eco parking` · `eco parking-buy` (when enabled in config)
            **Dino parking:** `dino park` (save current character) · `dino list` · `dino delete` · `dino retrieve`
            **Ecosystem:** `ecosystem dashboard` (population snapshot)
            
            **Retrieve & logging in:** If your host enabled **on-disk restore** (`dino_park.playerdata_file` in the bot’s config), **`/evrima dino retrieve`** can rewrite your server save file. **Stop the dedicated server first** (or the game may overwrite the file), run retrieve, then start the server and join. Your character is whatever that save contained: **species, growth, and world position** match the snapshot captured at **park** time (plus any **logout autosave** updates if configured).
            
            Staff commands: `/evrima-mod adminhelp` (moderator) · `/evrima-admin …` (admin)
            """;

    private static final String EVRIMA_MOD_ADMIN_HELP = """
            **Moderator** (`/evrima-mod`)
            `adminhelp` — this list
            `whois` — Discord user or `player` (SteamID64 / in-game name) + linked Steam + getplayerdata
            `timeout` — Discord timeout (not in-game)
            
            **Admin** (`/evrima-admin`) — RCON + config writes (see Integrations → permissions): announce, playerlist, kick, ban, dm, getplayer, wipecorpses, reload, save, unlink, give, AI / species / corpse-wipe controls, …
            
            **Head admin** (`/evrima-head check`)
            """;

    private void dispatchEvrima(SlashCommandInteractionEvent event) throws SQLException {
        String group = event.getSubcommandGroup();
        String sub = event.getSubcommandName();
        if ("help".equals(sub)) {
            event.reply(truncate(EVRIMA_PUBLIC_HELP.strip(), 2000)).setEphemeral(true).queue();
            return;
        }
        if (group == null || sub == null) {
            event.reply("Use **`/evrima help`** for public commands.").setEphemeral(true).queue();
            return;
        }
        switch (group) {
            case "link" -> handleLink(event, sub);
            case "account" -> handleAccount(event, sub);
            case "eco" -> handleEco(event, sub);
            case "dino" -> handleDino(event, sub);
            case "ecosystem" -> handleEcosystem(event, sub);
            default -> event.reply("Unknown command group.").setEphemeral(true).queue();
        }
    }

    private void dispatchEvrimaMod(SlashCommandInteractionEvent event) {
        String sub = event.getSubcommandName();
        if (sub == null) {
            event.reply("Unknown subcommand.").setEphemeral(true).queue();
            return;
        }
        handleMod(event, sub);
    }

    private void dispatchEvrimaAdmin(SlashCommandInteractionEvent event) {
        String sub = event.getSubcommandName();
        if (sub == null) {
            event.reply("Unknown subcommand.").setEphemeral(true).queue();
            return;
        }
        if ("give".equals(sub)) {
            handleAdminGive(event);
            return;
        }
        handleAdmin(event, sub);
    }

    private void dispatchEvrimaHead(SlashCommandInteractionEvent event) {
        String sub = event.getSubcommandName();
        Member member = event.getMember();
        if (!"check".equals(sub)) {
            event.reply("Unknown subcommand.").setEphemeral(true).queue();
            return;
        }
        if (!permissions.isAtLeast(member, StaffTier.HEAD_ADMIN)) {
            event.reply(permissions.denyHeadAdminMessage(member)).setEphemeral(true).queue();
            return;
        }
        event.reply("Head-admin tier OK. Add future server-control commands under `/evrima-head`.")
                .setEphemeral(true).queue();
    }

    private void handleLink(SlashCommandInteractionEvent event, String sub) throws SQLException {
        String uid = event.getUser().getId();
        if ("start".equals(sub)) {
            event.deferReply(true).queue(hook -> {
                try {
                    String code = randomCode(6);
                    long exp = java.time.Instant.now().getEpochSecond() + live.get().linkCodeTtlMinutes() * 60L;
                    database.putLinkCode(code, uid, exp);
                    User user = event.getUser();
                    user.openPrivateChannel().queue(
                            pc -> pc.sendMessage(
                                    "Your Isle linking code: **" + code + "**\nIt expires in "
                                            + live.get().linkCodeTtlMinutes()
                                            + " minutes.\nRun `/evrima link complete` with this code and your SteamID64."
                            ).queue(
                                    s -> hookEditEphemeral(hook, "Check your DMs for a linking code."),
                                    f -> hookEditEphemeral(hook,
                                            "Could not DM you — enable DMs from this server and try again.")
                            ),
                            f -> hookEditEphemeral(hook, "Could not open a DM channel with you.")
                    );
                } catch (SQLException e) {
                    LOG.error("link start", e);
                    hookEditEphemeral(hook, "Database error — check logs.");
                }
            }, f -> LOG.error("deferReply (link start) failed", f));
            return;
        }
        if ("complete".equals(sub)) {
            String code = requiredString(event, "code").trim().toUpperCase();
            String steam = requiredString(event, "steam_id").trim();
            if (!steamIdLooksValid(steam)) {
                event.reply("SteamID64 should be numeric (typically 17 digits).").setEphemeral(true).queue();
                return;
            }
            Optional<String> owner = database.consumeLinkCode(code, uid);
            if (owner.isEmpty()) {
                event.reply("Invalid or expired code (or code belongs to another user).").setEphemeral(true).queue();
                return;
            }
            Optional<String> other = database.findDiscordForSteam(steam);
            if (other.isPresent() && !other.get().equals(uid)) {
                event.reply("That SteamID64 is already linked to another Discord account.").setEphemeral(true).queue();
                return;
            }
            database.upsertSteamLink(uid, steam);
            database.appendAudit(uid, "link_complete", steam);
            event.reply("Linked SteamID64 `" + steam + "` to your Discord.").setEphemeral(true).queue();
            return;
        }
        event.reply("Unknown link subcommand.").setEphemeral(true).queue();
    }

    private void handleAccount(SlashCommandInteractionEvent event, String sub) throws SQLException {
        if ("debug".equals(sub)) {
            Member m = event.getMember();
            StaffTier t = permissions.tier(m);
            String msg = "**Effective tier:** `" + t + "`\n"
                    + "**Your role IDs (this server):** " + permissions.formatMemberRoleIds(m) + "\n"
                    + "**Matches bot staff lists:** " + permissions.matchingConfiguredRoleIds(m) + "\n"
                    + "**" + permissions.formatConfiguredRoleSummary() + "**\n\n"
                    + "head_admin and admin both count for `/evrima-admin`. "
                    + "If lists look right but tier is PLAYER, confirm the bot has **Server Members Intent** and restart.";
            event.reply(truncate(msg, 2000)).setEphemeral(true).queue();
            return;
        }
        if (!"show".equals(sub)) {
            event.reply("Unknown account subcommand.").setEphemeral(true).queue();
            return;
        }
        Optional<String> steam = database.findSteamIdForDiscord(event.getUser().getId());
        if (steam.isEmpty()) {
            event.reply("No Steam account linked. Use `/evrima link start`.").setEphemeral(true).queue();
        } else {
            event.reply("Linked SteamID64: `" + steam.get() + "`").setEphemeral(true).queue();
        }
    }

    private void handleAdmin(SlashCommandInteractionEvent event, String sub) {
        Member member = event.getMember();
        if (!permissions.isAtLeast(member, StaffTier.ADMIN)) {
            event.reply(truncate(permissions.denyAdminMessage(member), 2000)).setEphemeral(true).queue();
            return;
        }
        event.deferReply(true).queue(
                hook -> {
                    try {
                        switch (sub) {
                            case "announce" -> {
                                String msg = requiredString(event, "message");
                                String out = rcon.run("announce " + msg.replace('\n', ' '));
                                database.appendAudit(event.getUser().getId(), "rcon_announce", truncate(msg, 500));
                                hookEditEphemeral(hook, "RCON: " + out);
                            }
                            case "playerlist" -> {
                                String out = rcon.run("playerlist");
                                database.appendAudit(event.getUser().getId(), "rcon_playerlist", "");
                                hookEditEphemeral(hook, "```\n" + out + "\n```");
                            }
                            case "kick" -> {
                                String steam = resolveAdminPlayerTarget(requiredString(event, "player"));
                                String reason = requiredString(event, "reason");
                                // Space after SteamID — same wire format as before (many builds accept this).
                                String out = rcon.run("kick " + steam + " " + reason.replace('\n', ' '));
                                database.appendAudit(event.getUser().getId(), "rcon_kick", steam + " | " + truncate(reason, 200));
                                hookEditEphemeral(hook, "RCON: " + out);
                            }
                            case "ban" -> {
                                String steam = resolveAdminPlayerTarget(requiredString(event, "player"));
                                String reason = requiredString(event, "reason");
                                OptionMapping minOpt = event.getOption("minutes");
                                int minutes = minOpt == null ? 0 : Math.max(0, (int) minOpt.getAsLong());
                                String out = rcon.run(EvrimaRcon.lineBan("Unknown", steam, reason, minutes));
                                database.appendAudit(event.getUser().getId(), "rcon_ban",
                                        steam + " | " + minutes + "m | " + truncate(reason, 200));
                                hookEditEphemeral(hook, "RCON: " + out);
                            }
                            case "dm" -> {
                                String steam = resolveAdminPlayerTarget(requiredString(event, "player"));
                                String message = requiredString(event, "message");
                                String out = rcon.run(EvrimaRcon.lineDirectMessage(steam, message));
                                database.appendAudit(event.getUser().getId(), "rcon_dm", steam);
                                hookEditEphemeral(hook, "RCON: " + out);
                            }
                            case "getplayer" -> {
                                String steam = resolveAdminPlayerTarget(requiredString(event, "player"));
                                String out = rcon.run("getplayerdata " + steam);
                                String filtered = EvrimaRcon.filterGetplayerdataResponseForSteamId(out, steam);
                                database.appendAudit(event.getUser().getId(), "rcon_getplayer", steam);
                                if (filtered.isEmpty()) {
                                    hookEditEphemeral(hook, "No `getplayerdata` row for SteamID `" + steam
                                            + "` (not spawned in, id mismatch, or empty RCON response). Raw (truncated):\n```\n"
                                            + truncate(out, 1200) + "\n```");
                                } else {
                                    Optional<String> dip = PlayerdataIpExtract.preferredIpv4(filtered);
                                    String ipLine = dip.map(s -> "**IP:** `" + s + "` *(from getplayerdata)*")
                                            .orElse("**IP:** *(not present in getplayerdata text)*");
                                    hookEditEphemeral(hook, ipLine + "\n\n```\n" + truncate(filtered, 1800) + "\n```");
                                }
                            }
                            case "wipecorpses" -> {
                                String out = rcon.run("wipecorpses");
                                database.appendAudit(event.getUser().getId(), "rcon_wipecorpses", "");
                                hookEditEphemeral(hook, "RCON: " + out);
                            }
                            case "reload" -> {
                                reloadYamlFromDisk();
                                database.appendAudit(event.getUser().getId(), "admin_config_reload", "");
                                hookEditEphemeral(hook,
                                        "Reloaded **`config.yml`**, **`species-taxonomy.yml`**, **`kill-flavor.yml`** (when kill flavor is on), and **`auto-messages.yml`** from disk into memory.\n"
                                                + "In-game auto-announce interval/mode/messages apply immediately; other schedulers may still need a **bot restart** if you changed "
                                                + "`database.path`, Discord token, or poll/topic/dashboard timing.\n"
                                                + "Role checks and RCON settings apply immediately for new operations.");
                            }
                            case "save" -> {
                                String out = rcon.run("save");
                                database.appendAudit(event.getUser().getId(), "rcon_save", "");
                                hookEditEphemeral(hook, "RCON: " + out);
                            }
                            case "unlink" -> {
                                User target = requiredUser(event, "user");
                                database.deleteSteamLink(target.getId());
                                database.appendAudit(event.getUser().getId(), "admin_unlink", target.getId());
                                hookEditEphemeral(hook, "Removed stored Steam link for " + target.getAsMention());
                            }
                            case "ai-toggle" -> {
                                String out = rcon.run("toggleai");
                                LOG.info("evrima-admin ai-toggle: {} ({}) ran RCON toggleai",
                                        event.getUser().getName(), event.getUser().getId());
                                database.appendAudit(event.getUser().getId(), "rcon_toggleai", "");
                                hookEditEphemeral(hook,
                                        "**What this does:** `toggleai` **flips** the server’s AI master switch (On→Off or Off→On). "
                                                + "It does **not** target a species and does **not** delete existing AI dinos.\n"
                                                + "Run it again to flip back.\n\n"
                                                + "```\n" + truncate(out, 1700) + "\n```");
                            }
                            case "ai-density" -> {
                                double v = requiredDouble(event, "value");
                                boolean hadAdaptive = live.get().adaptiveAiDensityEnabled();
                                if (hadAdaptive) {
                                    applyYamlMutation(y -> ConfigYamlUpdater.setAdaptiveAiDensityEnabled(y, false));
                                }
                                String out = rcon.run(EvrimaRcon.lineAidensity(v));
                                String uid = event.getUser().getId();
                                LOG.info("evrima-admin ai-density: {} ({}) set AI spawn density to {} via RCON {}{}",
                                        event.getUser().getName(), uid, v, EvrimaRcon.lineAidensity(v),
                                        hadAdaptive ? " (adaptive_ai_density disabled in config.yml)" : "");
                                database.appendAudit(uid, "rcon_aidensity", String.valueOf(v));
                                if (hadAdaptive) {
                                    database.appendAudit(uid, "adaptive_ai_density_disabled_for_manual_aidensity", "");
                                }
                                StringBuilder msg = new StringBuilder(512);
                                if (hadAdaptive) {
                                    msg.append("**Adaptive AI density** was **on** — it is now **off** in `config.yml` so the "
                                            + "scheduler will not override this `aidensity`.\nSet `adaptive_ai_density.enabled` "
                                            + "back to `true` when you want tiers to run again.\n\n");
                                }
                                msg.append("**What this does:** `aidensity` sets how strongly **new** AI can spawn (multiplier; "
                                        + "`0` usually means no new AI spawns). It does **not** mass-kill AI already in the world.\n\n"
                                        + "```\n").append(truncate(out, 1700)).append("\n```");
                                hookEditEphemeral(hook, truncate(msg.toString(), 2000));
                            }
                            case "ai-classes" -> {
                                String classes = requiredString(event, "classes").trim();
                                String rconLine = "disableaiclasses " + classes.replace('\n', ' ');
                                String out = rcon.run(rconLine);
                                LOG.info("evrima-admin ai-classes: {} ({}) disabled AI classes via RCON: {}",
                                        event.getUser().getName(), event.getUser().getId(), truncate(classes, 500));
                                database.appendAudit(event.getUser().getId(), "rcon_disableaiclasses", truncate(classes, 200));
                                hookEditEphemeral(hook,
                                        "**What this does:** `disableaiclasses` tells the game to **stop those AI types** "
                                                + "(internal class names like `boar`) from spawning.\n"
                                                + "**Not a toggle:** running it again with the same class still means “disabled”; "
                                                + "this bot has **no** matching “enable AI class” RCON. "
                                                + "Undo may need a restart, host tools, or `updateplayables` if your build documents it.\n\n"
                                                + "```\n" + truncate(out, 1700) + "\n```");
                            }
                            case "ai-stop-spawns" -> {
                                String uid = event.getUser().getId();
                                boolean hadAdaptive = live.get().adaptiveAiDensityEnabled();
                                if (hadAdaptive) {
                                    applyYamlMutation(y -> ConfigYamlUpdater.setAdaptiveAiDensityEnabled(y, false));
                                }
                                StringBuilder acc = new StringBuilder();
                                if (hadAdaptive) {
                                    acc.append("**Adaptive AI density** was **on** — it is now **off** in `config.yml` so the "
                                            + "scheduler will not override **aidensity 0**.\nSet `adaptive_ai_density.enabled` "
                                            + "back to `true` when you want tiers to run again.\n\n");
                                }
                                acc.append("**`/evrima-admin ai-stop-spawns`** runs **`aidensity 0`** only — it reduces or stops **new** AI spawns.\n");
                                acc.append("**It does not:** delete **living** AI (use **Insert → Admin → Wipe AI** in Evrima), "
                                        + "clean corpses (`wipecorpses`), or flip the global AI switch (`ai-toggle`).\n\n");
                                acc.append("**aidensity 0**\n```\n")
                                        .append(truncate(rcon.run(EvrimaRcon.lineAidensity(0)), 1200))
                                        .append("\n```\n");
                                acc.append("\n**Restore:** `/evrima-admin ai-density` with your usual multiplier.");
                                LOG.info("evrima-admin ai-stop-spawns: {} ({}) set AI density to 0 via RCON aidensity 0{}",
                                        event.getUser().getName(), uid,
                                        hadAdaptive ? " (adaptive_ai_density disabled in config.yml)" : "");
                                database.appendAudit(uid, "rcon_ai_stop_spawns", "");
                                if (hadAdaptive) {
                                    database.appendAudit(uid, "adaptive_ai_density_disabled_for_manual_aidensity", "");
                                }
                                hookEditEphemeral(hook, truncate(acc.toString(), 2000));
                            }
                            case "ai-wipe" -> {
                                String uid = event.getUser().getId();
                                String msg = "**Living AI wipe (The Isle Evrima)**\n\n"
                                        + "The **Evrima dedicated RCON** commands this bot sends are the usual **named binary opcodes** "
                                        + "(`wipecorpses`, `toggleai`, `aidensity`, `disableaiclasses`, `toggleailearning`, … — same set as "
                                        + "common Evrima RCON docs / panels). **That set does not include** “delete all living wild AI.”\n\n"
                                        + "**`wipecorpses`** only clears **corpses**. **`toggleai` / `aidensity` / `disableaiclasses`** do not "
                                        + "replace **Insert → Admin → Wipe AI** in the game client.\n\n"
                                        + "This slash command **does not** send `custom` / free-text console execs — only the mapped Evrima RCON verbs above.";
                                database.appendAudit(uid, "rcon_ai_wipe_info", "");
                                hookEditEphemeral(hook, msg);
                            }
                            case "ai-learning" -> {
                                String out = rcon.run("toggleailearning");
                                LOG.info("evrima-admin ai-learning: {} ({}) ran RCON toggleailearning",
                                        event.getUser().getName(), event.getUser().getId());
                                database.appendAudit(event.getUser().getId(), "rcon_toggleailearning", "");
                                hookEditEphemeral(hook, "RCON `toggleailearning`:\n```\n" + truncate(out, 1800) + "\n```");
                            }
                            case "species-control" -> {
                                String mode = requiredString(event, "mode").trim().toLowerCase();
                                if ("status".equals(mode)) {
                                    hookEditEphemeral(hook, speciesControlStatusText());
                                    break;
                                }
                                if ("on".equals(mode) || "enable".equals(mode)) {
                                    if (!speciesControl.hasConfiguredCaps()) {
                                        hookEditEphemeral(hook, "species_population_control has no configured caps. Set `species_population_control.caps` first.");
                                        break;
                                    }
                                    applyYamlMutation(y -> ConfigYamlUpdater.setSpeciesPopulationControlEnabled(y, true));
                                    database.appendAudit(event.getUser().getId(), "species_control_toggle", "on");
                                    LOG.info("evrima-admin species-control: {} ({}) turned species_population_control ON (config.yml)",
                                            event.getUser().getName(), event.getUser().getId());
                                    hookEditEphemeral(hook, "Species population control: **ON** (saved to `config.yml`)");
                                    break;
                                }
                                if ("off".equals(mode) || "disable".equals(mode)) {
                                    boolean mergedPlayables;
                                    try {
                                        mergedPlayables = speciesControl.restorePlayablesBeforeDisablingSpeciesControl();
                                    } catch (IOException e) {
                                        LOG.warn("species_control disable: restore playables failed: {}", e.toString());
                                        hookEditEphemeral(hook, truncate(
                                                "Could not restore playables via RCON — `config.yml` was **not** changed: "
                                                        + e.getMessage(), 2000));
                                        break;
                                    }
                                    applyYamlMutation(y -> ConfigYamlUpdater.setSpeciesPopulationControlEnabled(y, false));
                                    database.appendAudit(event.getUser().getId(), "species_control_toggle", "off");
                                    LOG.info("evrima-admin species-control: {} ({}) turned species_population_control OFF (config.yml){}",
                                            event.getUser().getName(), event.getUser().getId(),
                                            mergedPlayables ? "; merged caps into RCON playables first" : "");
                                    if (mergedPlayables) {
                                        hookEditEphemeral(hook,
                                                "Merged **every species under `species_population_control.caps`** back into "
                                                        + "the RCON playables list (with current `getplayables`), then set "
                                                        + "species population control: **OFF** (saved to `config.yml`).");
                                    } else {
                                        hookEditEphemeral(hook,
                                                "Species population control: **OFF** (saved to `config.yml`). "
                                                        + "*(No `caps` entries — skipped RCON `updateplayables`.)*");
                                    }
                                    break;
                                }
                                hookEditEphemeral(hook, "Invalid mode. Use `on`, `off`, or `status`.");
                            }
                            case "species-cap-set" -> {
                                String species = requiredString(event, "species").trim();
                                int cap = (int) requiredLong(event, "cap");
                                if (cap < 0 || cap > 500) {
                                    hookEditEphemeral(hook, "cap must be between 0 and 500.");
                                    break;
                                }
                                String canonical = ConfigYamlUpdater.requireCanonicalSpeciesCapKey(species);
                                applyYamlMutation(y -> ConfigYamlUpdater.setSpeciesCapWithBundledKey(y, canonical, cap));
                                database.appendAudit(event.getUser().getId(), "species_cap_set", canonical + "=" + cap);
                                LOG.info("evrima-admin species-cap-set: {} ({}) set cap {} = {} (config.yml)",
                                        event.getUser().getName(), event.getUser().getId(), canonical, cap);
                                hookEditEphemeral(hook, "Updated `config.yml`: species cap **`" + canonical + "` = " + cap + "**");
                            }
                            case "species-cap-clear" -> {
                                String species = requiredString(event, "species").trim();
                                String canonical = ConfigYamlUpdater.requireCanonicalSpeciesCapKey(species);
                                applyYamlMutation(y -> ConfigYamlUpdater.clearSpeciesCapToExampleDefault(y, canonical));
                                database.appendAudit(event.getUser().getId(), "species_cap_clear", canonical);
                                LOG.info("evrima-admin species-cap-clear: {} ({}) reset cap {} (config.yml)",
                                        event.getUser().getName(), event.getUser().getId(), canonical);
                                hookEditEphemeral(hook, "Reset **`" + canonical + "`** cap in `config.yml` to the value from bundled defaults (or **0** if not listed there).");
                            }
                            case "species-cap-list" -> hookEditEphemeral(hook, speciesCapListText());
                            case "corpse-wipe-control" -> {
                                String mode = requiredString(event, "mode").trim().toLowerCase(Locale.ROOT);
                                if ("status".equals(mode)) {
                                    hookEditEphemeral(hook, corpseWipeStatusText());
                                    break;
                                }
                                if ("on".equals(mode) || "enable".equals(mode) || "true".equals(mode)) {
                                    applyYamlMutation(y -> ConfigYamlUpdater.setScheduledWipecorpsesEnabled(y, "true"));
                                    database.appendAudit(event.getUser().getId(), "corpse_wipe_toggle", "on");
                                    hookEditEphemeral(hook, "Scheduled corpse wipes: **ON** (saved to `config.yml`)");
                                    break;
                                }
                                if ("off".equals(mode) || "disable".equals(mode) || "false".equals(mode)) {
                                    applyYamlMutation(y -> ConfigYamlUpdater.setScheduledWipecorpsesEnabled(y, "false"));
                                    database.appendAudit(event.getUser().getId(), "corpse_wipe_toggle", "off");
                                    hookEditEphemeral(hook, "Scheduled corpse wipes: **OFF** (saved to `config.yml`)");
                                    break;
                                }
                                if ("dynamic".equals(mode)) {
                                    applyYamlMutation(y -> ConfigYamlUpdater.setScheduledWipecorpsesEnabled(y, "dynamic"));
                                    database.appendAudit(event.getUser().getId(), "corpse_wipe_toggle", "dynamic");
                                    hookEditEphemeral(hook, "Scheduled corpse wipes: **DYNAMIC** (saved to `config.yml`)");
                                    break;
                                }
                                hookEditEphemeral(hook, "Invalid mode. Use `on`, `off`, `dynamic`, or `status`.");
                            }
                            case "corpse-wipe-set" -> {
                                String key = requiredString(event, "key").trim().toLowerCase(Locale.ROOT);
                                String value = requiredString(event, "value").trim();
                                switch (key) {
                                    case "interval", "interval_minutes" -> {
                                        int v = parseIntInRange(value, 0, 10_080, "interval_minutes");
                                        applyYamlMutation(y -> ConfigYamlUpdater.setScheduledWipecorpsesIntervalMinutes(y, v));
                                        database.appendAudit(event.getUser().getId(), "corpse_wipe_set", "interval_minutes=" + v);
                                        hookEditEphemeral(hook, "Set `config.yml` scheduled_wipecorpses.interval_minutes = **" + v + "**");
                                    }
                                    case "warn", "warn_before", "warn_before_minutes" -> {
                                        int v = parseIntInRange(value, 0, 1_440, "warn_before_minutes");
                                        applyYamlMutation(y -> ConfigYamlUpdater.setScheduledWipecorpsesWarnBeforeMinutes(y, v));
                                        database.appendAudit(event.getUser().getId(), "corpse_wipe_set", "warn_before_minutes=" + v);
                                        hookEditEphemeral(hook, "Set `config.yml` scheduled_wipecorpses.warn_before_minutes = **" + v + "**");
                                    }
                                    case "message", "announce", "announce_message" -> {
                                        applyYamlMutation(y -> ConfigYamlUpdater.setScheduledWipecorpsesAnnounceMessage(y, value));
                                        database.appendAudit(event.getUser().getId(), "corpse_wipe_set", "announce_message");
                                        hookEditEphemeral(hook, "Updated `config.yml` scheduled_wipecorpses.announce_message.");
                                    }
                                    case "dynamic_max_players" -> {
                                        int v = parseIntInRange(value, 0, 1000, "dynamic_max_players");
                                        applyYamlMutation(y -> ConfigYamlUpdater.setScheduledWipecorpsesDynamicMaxPlayers(y, v));
                                        database.appendAudit(event.getUser().getId(), "corpse_wipe_set", "dynamic_max_players=" + v);
                                        hookEditEphemeral(hook, "Set `config.yml` scheduled_wipecorpses.dynamic_max_players = **" + v + "**");
                                    }
                                    case "dynamic_enable_percent" -> {
                                        int v = parseIntInRange(value, 0, 100, "dynamic_enable_percent");
                                        applyYamlMutation(y -> ConfigYamlUpdater.setScheduledWipecorpsesDynamicEnablePercent(y, v));
                                        database.appendAudit(event.getUser().getId(), "corpse_wipe_set", "dynamic_enable_percent=" + v);
                                        hookEditEphemeral(hook, "Set `config.yml` scheduled_wipecorpses.dynamic_enable_percent = **" + v + "%**");
                                    }
                                    case "dynamic_disable_grace_seconds" -> {
                                        int v = parseIntInRange(value, 0, 600, "dynamic_disable_grace_seconds");
                                        applyYamlMutation(y -> ConfigYamlUpdater.setScheduledWipecorpsesDynamicDisableGraceSeconds(y, v));
                                        database.appendAudit(event.getUser().getId(), "corpse_wipe_set", "dynamic_disable_grace_seconds=" + v);
                                        hookEditEphemeral(hook, "Set `config.yml` scheduled_wipecorpses.dynamic_disable_grace_seconds = **" + v + "s**");
                                    }
                                    default -> hookEditEphemeral(hook, "Unknown key. Use `interval_minutes`, `warn_before_minutes`, `announce_message`, `dynamic_max_players`, `dynamic_enable_percent`, or `dynamic_disable_grace_seconds`.");
                                }
                            }
                            case "corpse-wipe-clear" -> {
                                String key = requiredString(event, "key").trim().toLowerCase(Locale.ROOT);
                                switch (key) {
                                    case "interval", "interval_minutes" -> {
                                        applyYamlMutation(y -> ConfigYamlUpdater.resetScheduledWipecorpsesField(y, ScheduledWipeResetKey.INTERVAL_MINUTES));
                                        hookEditEphemeral(hook, "Reset `scheduled_wipecorpses.interval_minutes` in `config.yml` to the bundled default template.");
                                    }
                                    case "warn", "warn_before", "warn_before_minutes" -> {
                                        applyYamlMutation(y -> ConfigYamlUpdater.resetScheduledWipecorpsesField(y, ScheduledWipeResetKey.WARN_BEFORE_MINUTES));
                                        hookEditEphemeral(hook, "Reset `warn_before_minutes` in `config.yml` to bundled default.");
                                    }
                                    case "message", "announce", "announce_message" -> {
                                        applyYamlMutation(y -> ConfigYamlUpdater.resetScheduledWipecorpsesField(y, ScheduledWipeResetKey.ANNOUNCE_MESSAGE));
                                        hookEditEphemeral(hook, "Reset `announce_message` in `config.yml` to bundled default.");
                                    }
                                    case "enabled" -> {
                                        applyYamlMutation(y -> ConfigYamlUpdater.resetScheduledWipecorpsesField(y, ScheduledWipeResetKey.ENABLED));
                                        hookEditEphemeral(hook, "Reset `enabled` in `config.yml` to bundled default.");
                                    }
                                    case "dynamic_max_players" -> {
                                        applyYamlMutation(y -> ConfigYamlUpdater.resetScheduledWipecorpsesField(y, ScheduledWipeResetKey.DYNAMIC_MAX_PLAYERS));
                                        hookEditEphemeral(hook, "Reset `dynamic_max_players` in `config.yml` to bundled default.");
                                    }
                                    case "dynamic_enable_percent" -> {
                                        applyYamlMutation(y -> ConfigYamlUpdater.resetScheduledWipecorpsesField(y, ScheduledWipeResetKey.DYNAMIC_ENABLE_PERCENT));
                                        hookEditEphemeral(hook, "Reset `dynamic_enable_percent` in `config.yml` to bundled default.");
                                    }
                                    case "dynamic_disable_grace_seconds" -> {
                                        applyYamlMutation(y -> ConfigYamlUpdater.resetScheduledWipecorpsesField(y, ScheduledWipeResetKey.DYNAMIC_DISABLE_GRACE_SECONDS));
                                        hookEditEphemeral(hook, "Reset `dynamic_disable_grace_seconds` in `config.yml` to bundled default.");
                                    }
                                    case "all" -> {
                                        applyYamlMutation(ConfigYamlUpdater::resetScheduledWipecorpsesAll);
                                        hookEditEphemeral(hook, "Reset entire `scheduled_wipecorpses` section in `config.yml` to bundled defaults.");
                                    }
                                    default -> hookEditEphemeral(hook, "Unknown key. Use `enabled`, `interval_minutes`, `warn_before_minutes`, `announce_message`, `dynamic_max_players`, `dynamic_enable_percent`, `dynamic_disable_grace_seconds`, or `all`.");
                                }
                                database.appendAudit(event.getUser().getId(), "corpse_wipe_clear", key);
                            }
                            default -> hookEditEphemeral(hook, "Unknown admin subcommand.");
                        }
                    } catch (SQLException e) {
                        LOG.error("admin command db", e);
                        hookEditEphemeral(hook, "Database error — check logs.");
                    } catch (IOException e) {
                        LOG.warn("admin IO: {}", e.toString());
                        hookEditEphemeral(hook, "Operation failed: " + truncate(e.getMessage(), 1800));
                    } catch (IllegalStateException | IllegalArgumentException e) {
                        hookEditEphemeral(hook, truncate(e.getMessage(), 2000));
                    }
                },
                f -> LOG.error("deferReply (evrima-admin) failed", f));
    }

    private void handleMod(SlashCommandInteractionEvent event, String sub) {
        Member actor = event.getMember();
        if (!permissions.isAtLeast(actor, StaffTier.MODERATOR)) {
            event.reply(truncate(permissions.denyModeratorMessage(actor), 2000)).setEphemeral(true).queue();
            return;
        }
        if ("adminhelp".equals(sub)) {
            event.reply(truncate(EVRIMA_MOD_ADMIN_HELP.strip(), 2000)).setEphemeral(true).queue();
            return;
        }
        if ("whois".equals(sub)) {
            OptionMapping userOpt = event.getOption("user");
            OptionMapping playerOpt = event.getOption("player");
            String playerRaw = playerOpt != null ? playerOpt.getAsString() : null;
            boolean hasPlayer = playerRaw != null && !playerRaw.isBlank();
            boolean hasUser = userOpt != null;
            if (hasUser && hasPlayer) {
                event.reply("Use **either** `user` (Discord) **or** `player` (SteamID64 / in-game name), not both.")
                        .setEphemeral(true).queue();
                return;
            }
            if (!hasUser && !hasPlayer) {
                event.reply("Provide **`user`** (Discord account) or **`player`** (SteamID64 or in-game name from `/evrima-admin playerlist`).")
                        .setEphemeral(true).queue();
                return;
            }
            if (hasUser) {
                User target = userOpt.getAsUser();
                event.deferReply(true).queue(hook -> {
                    try {
                        Optional<String> steam = database.findSteamIdForDiscord(target.getId());
                        StringBuilder sb = new StringBuilder();
                        sb.append("Discord: ").append(target.getName()).append(" (").append(target.getId()).append(")\n");
                        if (steam.isEmpty()) {
                            sb.append("Steam: *(not linked)*");
                        } else {
                            sb.append("SteamID64: `").append(steam.get()).append("`");
                            appendFilteredGetplayerdataForWhois(sb, steam.get());
                        }
                        database.appendAudit(event.getUser().getId(), "mod_whois", "discord:" + target.getId());
                        hookEditEphemeral(hook, sb.toString());
                    } catch (SQLException e) {
                        LOG.error("mod whois", e);
                        hookEditEphemeral(hook, "Database error — check logs.");
                    }
                }, f -> LOG.error("deferReply (evrima-mod whois) failed", f));
                return;
            }
            String query = playerRaw.strip();
            event.deferReply(true).queue(hook -> {
                try {
                    String steam = resolveAdminPlayerTarget(query);
                    StringBuilder sb = new StringBuilder();
                    sb.append("**Lookup:** `").append(truncate(query.replace("`", "'"), 200)).append("`\n");
                    sb.append("**SteamID64:** `").append(steam).append("`");
                    appendLinkedDiscordLineForSteam(sb, event.getJDA(), steam);
                    appendFilteredGetplayerdataForWhois(sb, steam);
                    database.appendAudit(event.getUser().getId(), "mod_whois", "player:" + truncate(query, 240));
                    hookEditEphemeral(hook, sb.toString());
                } catch (IOException e) {
                    LOG.warn("mod whois RCON: {}", e.toString());
                    hookEditEphemeral(hook, "RCON failed: " + truncate(e.getMessage(), 1800));
                } catch (IllegalStateException e) {
                    hookEditEphemeral(hook, truncate(e.getMessage(), 2000));
                } catch (SQLException e) {
                    LOG.error("mod whois audit", e);
                    hookEditEphemeral(hook, "Database error — check logs.");
                }
            }, f -> LOG.error("deferReply (evrima-mod whois) failed", f));
            return;
        }
        if ("timeout".equals(sub)) {
            Member target = requiredMember(event, "user");
            long rawMin = requiredLong(event, "minutes");
            if (rawMin < 1 || rawMin > 40320) {
                event.reply("minutes must be between 1 and 40320.").setEphemeral(true).queue();
                return;
            }
            int minutes = Math.toIntExact(rawMin);
            if (event.getGuild() == null) {
                event.reply("Run this in a server.").setEphemeral(true).queue();
                return;
            }
            var guild = event.getGuild();
            event.deferReply(true).queue(hook -> guild.timeoutFor(target, Duration.ofMinutes(minutes))
                    .reason("Timeout via EvrimaServerBot by " + event.getUser().getName())
                    .queue(
                            s -> {
                                try {
                                    database.appendAudit(event.getUser().getId(), "mod_timeout",
                                            target.getId() + "|" + minutes + "m");
                                } catch (SQLException e) {
                                    LOG.warn("audit log failed", e);
                                }
                                hookEditEphemeral(hook, "Timed out " + target.getAsMention() + " for " + minutes + " minutes.");
                            },
                            f -> hookEditEphemeral(hook, "Discord timeout failed: " + f.getMessage())
                    ), f -> LOG.error("deferReply (evrima-mod timeout) failed", f));
            return;
        }
        event.reply("Unknown mod subcommand.").setEphemeral(true).queue();
    }

    private void handleEcosystem(SlashCommandInteractionEvent event, String sub) {
        if (!"dashboard".equals(sub)) {
            event.reply("Unknown ecosystem subcommand.").setEphemeral(true).queue();
            return;
        }
        boolean fresh = optionalBoolean(event, "fresh", false);
        event.deferReply(false).queue(hook -> {
            try {
                PopulationDashboardService.SnapshotResult res = population.snapshot(fresh);
                SpeciesTaxonomy tax = population.taxonomy();
                BotConfig ecoCfg = live.get();
                MessageEmbed embed = EcosystemEmbeds.build(
                        ecoCfg.ecosystemTitle(),
                        res.data(),
                        tax,
                        event.getGuild(),
                        ecoCfg.speciesPopulationControlEnabled(),
                        ecoCfg.speciesPopulationCaps());
                hook.editOriginalEmbeds(embed).queue(
                        null,
                        err -> LOG.warn("ecosystem dashboard editOriginalEmbeds: {}", err.toString()));
            } catch (IOException e) {
                LOG.warn("ecosystem dashboard RCON: {}", e.toString());
                hookEditEphemeral(hook, "RCON failed: " + truncate(e.getMessage(), 1800));
            }
        }, f -> LOG.error("deferReply (ecosystem dashboard) failed", f));
    }

    private void handleEco(SlashCommandInteractionEvent event, String sub) throws SQLException {
        String uid = event.getUser().getId();
        if ("balance".equals(sub)) {
            int bal = database.getBalance(uid);
            event.reply("Your balance: **" + bal + "** points.").setEphemeral(true).queue();
            return;
        }
        if ("spin".equals(sub)) {
            int today = yyyymmddUtc(LocalDate.now(ZoneOffset.UTC));
            OptionalLongCompat last = OptionalLongCompat.of(database.lastSpinDay(uid));
            if (last.present && last.value == today) {
                event.reply("You already used your daily spin today (UTC).").setEphemeral(true).queue();
                return;
            }
            int lo = Math.min(live.get().dailySpinMin(), live.get().dailySpinMax());
            int hi = Math.max(live.get().dailySpinMin(), live.get().dailySpinMax());
            int roll = ThreadLocalRandom.current().nextInt(lo, hi + 1);
            database.setLastSpinDay(uid, today);
            database.addBalance(uid, roll);
            database.appendAudit(uid, "eco_spin", String.valueOf(roll));
            event.reply("Daily spin: **+" + roll + "** points. New balance: **" + database.getBalance(uid) + "**.")
                    .setEphemeral(false)
                    .queue();
            return;
        }
        if ("parking".equals(sub)) {
            EconomyParkingSlotsConfig ps = live.get().economyParkingSlots();
            if (!ps.enabled()) {
                event.reply("Parking slot limits are **off** in config — you have **unlimited** dino parking slots.")
                        .setEphemeral(true).queue();
                return;
            }
            int extra = database.getExtraParkingSlots(uid);
            int used = database.countParkedSlots(uid);
            int cap = ps.capacityForPurchasedExtras(extra);
            int bal = database.getBalance(uid);
            boolean atPurchaseCap = ps.defaultSlots() + extra >= ps.maxSlots();
            int nextPrice = atPurchaseCap ? -1 : ps.priceForNextExtraSlot(extra);
            StringBuilder sb = new StringBuilder();
            sb.append("**Parking slots**\n")
                    .append("Using: **").append(used).append(" / ").append(cap).append("**\n")
                    .append("Free (default): **").append(ps.defaultSlots()).append("**\n")
                    .append("Purchased extras: **").append(extra).append("**\n")
                    .append("Server cap: **").append(ps.maxSlots()).append("**\n")
                    .append("Balance: **").append(bal).append("** points\n");
            if (atPurchaseCap) {
                sb.append("\nYou **cannot** buy more slots (at **max_slots**).");
            } else {
                sb.append("\nNext extra slot costs: **").append(nextPrice).append("** points — `/evrima eco parking-buy`.\n")
                        .append("Formula: `base_price_per_slot × price_multiplier^purchased_extras` (see `economy.parking_slots`).");
            }
            event.reply(truncate(sb.toString(), 2000)).setEphemeral(true).queue();
            LOG.info("eco parking: discordUserId={} used={} cap={} extra={} balance={}", uid, used, cap, extra, bal);
            return;
        }
        if ("parking-buy".equals(sub)) {
            EconomyParkingSlotsConfig ps = live.get().economyParkingSlots();
            Database.ParkingSlotPurchaseResult buy = database.purchaseParkingExtraSlot(uid, ps);
            if (!buy.ok()) {
                event.reply(buy.errorMessage()).setEphemeral(true).queue();
                LOG.info("eco parking-buy denied: discordUserId={} detail={}", uid, buy.errorMessage());
                return;
            }
            int bal = database.getBalance(uid);
            int cap = ps.capacityForPurchasedExtras(buy.extraSlotsNow());
            database.appendAudit(uid, "eco_parking_buy", "spent=" + buy.pointsSpent() + "|extra=" + buy.extraSlotsNow());
            event.reply("Purchased **+1** parking slot for **" + buy.pointsSpent() + "** points.\n"
                            + "Total capacity: **" + cap + "** slots (purchased extras: **" + buy.extraSlotsNow() + "**).\n"
                            + "Balance: **" + bal + "**.")
                    .setEphemeral(true).queue();
            LOG.info("eco parking-buy: discordUserId={} spent={} extraNow={} cap={} balance={}",
                    uid, buy.pointsSpent(), buy.extraSlotsNow(), cap, bal);
            return;
        }
        event.reply("Unknown eco subcommand.").setEphemeral(true).queue();
    }

    private void handleAdminGive(SlashCommandInteractionEvent event) {
        Member member = event.getMember();
        if (!permissions.isAtLeast(member, StaffTier.ADMIN)) {
            event.reply(truncate(permissions.denyAdminMessage(member), 2000)).setEphemeral(true).queue();
            return;
        }
        User target = requiredUser(event, "user");
        long rawAmt = requiredLong(event, "amount");
        if (rawAmt < 0 || rawAmt > 1_000_000_000L) {
            event.reply("amount must be between 0 and 1000000000.").setEphemeral(true).queue();
            return;
        }
        int amount = Math.toIntExact(rawAmt);
        event.deferReply(true).queue(hook -> {
            try {
                database.addBalance(target.getId(), amount);
                database.appendAudit(event.getUser().getId(), "eco_give", target.getId() + "|" + amount);
                int bal = database.getBalance(target.getId());
                hookEditEphemeral(hook, "Gave **" + amount + "** points to " + target.getAsMention()
                        + " (balance **" + bal + "**).");
            } catch (SQLException e) {
                LOG.error("admin give", e);
                hookEditEphemeral(hook, "Database error — check logs.");
            }
        }, f -> LOG.error("deferReply (evrima-admin give) failed", f));
    }

    @FunctionalInterface
    private interface YamlMutation {
        void accept(Path configYaml) throws IOException;
    }

    /**
     * Writes {@code config.yml} then reloads {@link LiveBotConfig}. Use for every admin subcommand that changes
     * YAML-backed bot settings (add {@link com.isle.evrima.bot.config.ConfigYamlUpdater} methods for new keys).
     */
    private void applyYamlMutation(YamlMutation mutation) throws IOException {
        synchronized (live) {
            mutation.accept(live.yamlPath());
            live.reloadFromDisk();
            speciesControl.invalidateConfigCache();
            corpseWipe.onConfigReloaded();
        }
    }

    private void hookEditEphemeral(InteractionHook hook, String text) {
        String t = truncate(text, 2000);
        if (t.isBlank()) {
            t = "(no output)";
        }
        hook.editOriginal(t).queue(null, err -> LOG.warn("hook.editOriginal failed: {}", err.toString()));
    }

    private String speciesControlStatusText() {
        String state = speciesControl.isEnabled() ? "ON" : "OFF";
        Map<String, Integer> effective = speciesControl.effectiveCapsReadOnly();
        StringBuilder sb = new StringBuilder();
        int active = 0;
        for (Integer v : effective.values()) {
            if (v != null && v > 0) {
                active++;
            }
        }
        sb.append("Species population control: **").append(state).append("**\n")
                .append("Active caps: **").append(active).append("** species\n")
                .append("Interval: **").append(live.get().speciesPopulationControlIntervalSeconds()).append("s**\n")
                .append("Unlock offset: **").append(live.get().speciesPopulationControlUnlockBelowOffset()).append("**\n")
                .append("Announce changes: **").append(live.get().speciesPopulationControlAnnounceChanges()).append("**\n");
        sb.append("\n**Capped species (from `config.yml`):**\n");
        boolean any = false;
        for (Map.Entry<String, Integer> e : effective.entrySet()) {
            int cap = e.getValue() == null ? 0 : e.getValue();
            if (cap <= 0) {
                continue;
            }
            any = true;
            sb.append("- ").append(e.getKey()).append(": **").append(cap).append("**\n");
        }
        if (!any) {
            sb.append("- None (all caps are 0/unmanaged)\n");
        }
        return truncate(sb.toString().strip(), 2000);
    }

    private String speciesCapListText() {
        Map<String, Integer> effective = speciesControl.effectiveCapsReadOnly();
        StringBuilder sb = new StringBuilder();
        sb.append("**Species caps (`config.yml`):**\n");
        for (Map.Entry<String, Integer> e : effective.entrySet()) {
            sb.append("- ").append(e.getKey()).append(": **").append(e.getValue()).append("**\n");
        }
        return truncate(sb.toString().strip(), 2000);
    }

    private String corpseWipeStatusText() {
        int interval = corpseWipe.intervalMinutes();
        int warn = corpseWipe.warnBeforeMinutes();
        boolean warningActive = warn > 0 && interval > warn;
        return "Scheduled corpse wipes mode: **" + corpseWipe.enabledMode().toUpperCase(Locale.ROOT) + "**"
                + " (effective now: **" + (corpseWipe.isEffectivelyEnabled() ? "ON" : "OFF") + "**)\n"
                + "Interval: **" + interval + " min**\n"
                + "Warn before: **" + warn + " min**"
                + (warningActive ? "" : " _(warning disabled with current values)_")
                + "\nDynamic max players: **" + corpseWipe.dynamicMaxPlayers() + "**"
                + "\nDynamic enable percent: **" + corpseWipe.dynamicEnablePercent() + "%**"
                + "\nDynamic grace (both ways): **" + corpseWipe.dynamicDisableGraceSeconds() + "s**"
                + "\nAnnounce message: `" + truncate(corpseWipe.announceMessage(), 250) + "`";
    }

    private static int parseIntInRange(String s, int min, int max, String label) {
        try {
            int v = Integer.parseInt(s.trim());
            if (v < min || v > max) {
                throw new IllegalStateException(label + " must be between " + min + " and " + max + ".");
            }
            return v;
        } catch (NumberFormatException nfe) {
            throw new IllegalStateException(label + " must be a number.");
        }
    }

    /** Tiny helper to avoid java.util.OptionalLong + isEmpty() verbosity across Java versions. */
    private static final class OptionalLongCompat {
        final boolean present;
        final long value;

        private OptionalLongCompat(boolean present, long value) {
            this.present = present;
            this.value = value;
        }

        static OptionalLongCompat of(java.util.OptionalLong o) {
            return o.isPresent() ? new OptionalLongCompat(true, o.getAsLong()) : new OptionalLongCompat(false, 0L);
        }
    }

    private void handleDino(SlashCommandInteractionEvent event, String sub) throws SQLException {
        String uid = event.getUser().getId();
        Optional<String> steam = database.findSteamIdForDiscord(uid);
        if (steam.isEmpty()) {
            event.reply("Link Steam first: `/evrima link start`.").setEphemeral(true).queue();
            return;
        }
        if ("park".equals(sub)) {
            String label = optionalString(event, "label", "Slot");
            String steamId = steam.get();
            event.deferReply(true).queue(hook -> {
                try {
                    BotConfig cfg = live.get();
                    EconomyParkingSlotsConfig ps = cfg.economyParkingSlots();
                    int used = database.countParkedSlots(uid);
                    int extraOwned = database.getExtraParkingSlots(uid);
                    int cap = ps.capacityForPurchasedExtras(extraOwned);
                    OptionalLong overwriteIdOpt = database.findOwnedParkedIdByLabel(uid, label);
                    boolean overwrite = overwriteIdOpt.isPresent();
                    if (ps.enabled() && used >= cap && !overwrite) {
                        hook.editOriginal("Parking is full (**" + used + " / " + cap + "** slots). "
                                        + "Use `/evrima eco parking` for details, or `/evrima eco parking-buy` to spend points for more.")
                                .queue();
                        LOG.info("dino park rejected (full): discordUserId={} steamId64={} used={} cap={}", uid, steamId, used, cap);
                        return;
                    }

                    String raw = rcon.run("getplayerdata " + steamId);
                    String filtered = EvrimaRcon.filterGetplayerdataResponseForSteamId(raw, steamId);
                    Optional<byte[]> diskCap = PlayerdataFileRestore.captureIfPresent(
                            cfg.dinoParkPlayerdataFile(), cfg.configYamlPath(), steamId);

                    ParkedDinoPayload.Summary sum;
                    String json;
                    boolean diskOnly = false;
                    long now = Instant.now().getEpochSecond();
                    if (filtered.isBlank()) {
                        byte[] diskBytes = diskCap.orElse(null);
                        if (diskBytes == null || diskBytes.length == 0) {
                            hook.editOriginal("Could not read **your** `getplayerdata` row (you may be **offline** or not spawned).\n\n"
                                            + "**Options:** join **in-game** on this character and try again, **or** ask your host to enable "
                                            + "`dino_park.playerdata_file` with a correct `path_template` so the bot can capture your save "
                                            + "from disk when RCON has no row.")
                                    .queue();
                            LOG.warn("dino park fail (no RCON row, no disk capture): discordUserId={} steamId64={}", uid, steamId);
                            return;
                        }
                        diskOnly = true;
                        sum = new ParkedDinoPayload.Summary(null, null, null);
                        String placeholderRaw = "(offline / no RCON getplayerdata row at park time — on-disk player file snapshot only)\n"
                                + "SteamID64: " + steamId;
                        json = ParkedDinoPayload.buildJson(now, placeholderRaw, sum, diskCap);
                        LOG.info("dino park using disk-only snapshot: discordUserId={} steamId64={} bytes={}",
                                uid, steamId, diskBytes.length);
                    } else {
                        sum = ParkedDinoPayload.parseSummaryFromGetplayerdata(filtered);
                        json = ParkedDinoPayload.buildJson(now, filtered, sum, diskCap);
                    }

                    long id;
                    if (overwrite) {
                        id = overwriteIdOpt.getAsLong();
                        boolean ok = database.overwriteParkedDino(id, uid, steamId, label, json, now);
                        if (!ok) {
                            hook.editOriginal("Could not overwrite existing slot. Please try again.").queue();
                            return;
                        }
                    } else {
                        id = database.insertParkedDino(uid, steamId, label, json);
                    }
                    database.setParkSessionSlot(uid, id);
                    database.appendAudit(uid, "dino_park", String.valueOf(id));
                    String diskNote = diskCap.isPresent()
                            ? "\n\n_On-disk player file **captured** for lossless retrieve (see `dino_park.playerdata_file`)._"
                            : (cfg.dinoParkPlayerdataFile().enabled() && cfg.dinoParkPlayerdataFile().captureFileOnPark()
                            ? "\n\n_No playerdata file found at `path_template` — retrieve will not be able to restore bytes until you park again with the file present._"
                            : "");
                    if (diskOnly) {
                        diskNote = "\n\n_Parked from **server save file** only (no live `getplayerdata` text). Species line in lists may show “—” until you park in-game once._"
                                + diskNote;
                    }
                    String verb = overwrite ? "Updated" : "Parked";
                    hook.editOriginal(verb + " **#" + id + "** — `" + truncate(label, 80) + "`\n**Snapshot:** "
                                    + sum.oneLine()
                                    + diskNote
                                    + "\n\n_Use `/evrima dino retrieve` to show the snapshot and optionally write the server file (see config)._")
                            .queue();
                    LOG.info("dino park {}: discordUserId={} steamId64={} slotId={} label=\"{}\" mode={} summary=\"{}\"",
                            overwrite ? "overwrite" : "insert",
                            uid, steamId, id, label, diskOnly ? "disk_only" : "rcon", dinoSummaryForLog(sum.oneLine()));
                } catch (SQLException e) {
                    LOG.warn("dino park db: {}", e.toString());
                    hook.editOriginal("Database error while saving slot.").queue();
                } catch (IOException e) {
                    LOG.warn("dino park rcon: {}", e.toString());
                    hook.editOriginal("RCON failed: " + truncate(e.getMessage(), 1800)).queue();
                }
            }, f -> LOG.error("deferReply (dino park) failed", f));
            return;
        }
        if ("list".equals(sub)) {
            var rows = database.listParked(uid);
            LOG.info("dino list: discordUserId={} steamId64={} count={}", uid, steam.get(), rows.size());
            if (rows.isEmpty()) {
                event.reply("No parking slots saved.").setEphemeral(true).queue();
                return;
            }
            StringBuilder sb = new StringBuilder();
            for (Database.ParkedRow r : rows) {
                sb.append("#").append(r.id()).append(" — ").append(r.label() == null ? "(no label)" : r.label())
                        .append(" — ").append(r.snapshotSummary())
                        .append(" — t=").append(r.parkedAtEpochSec()).append("\n");
            }
            event.reply(truncate(sb.toString(), 2000)).setEphemeral(true).queue();
            return;
        }
        if ("delete".equals(sub)) {
            long id = requiredLong(event, "id");
            if (database.deleteParked(id, uid)) {
                database.reconcileParkSessionAfterDelete(uid, id);
                database.appendAudit(uid, "dino_delete", String.valueOf(id));
                event.reply("Deleted slot **#" + id + "**.").setEphemeral(true).queue();
                LOG.info("dino delete: discordUserId={} steamId64={} slotId={}", uid, steam.get(), id);
            } else {
                event.reply("No slot **#" + id + "** for your account.").setEphemeral(true).queue();
                LOG.info("dino delete miss: discordUserId={} steamId64={} slotId={}", uid, steam.get(), id);
            }
            return;
        }
        if ("retrieve".equals(sub)) {
            long id = requiredLong(event, "id");
            event.deferReply(true).queue(hook -> {
                try {
                    Optional<Database.ParkedFullRow> row = database.findParked(id, uid);
                    if (row.isEmpty()) {
                        hook.editOriginal("No slot **#" + id + "** for your account.").queue();
                        LOG.info("dino retrieve miss: discordUserId={} steamId64={} slotId={}", uid, steam.get(), id);
                        return;
                    }
                    Database.ParkedFullRow r = row.get();
                    BotConfig cfg0 = live.get();
                    int cdSec = cfg0.dinoParkRetrieveCooldownSeconds();
                    if (cdSec > 0) {
                        long last = database.getParkRetrieveLastEpochSec(uid, id);
                        long nowSec = Instant.now().getEpochSecond();
                        if (last > 0 && nowSec - last < cdSec) {
                            long remain = cdSec - (nowSec - last);
                            hook.editOriginal("Retrieve cooldown: wait **" + remain + "** s before slot **#" + id
                                            + "** again (see `dino_park.retrieve_cooldown_seconds` in config).").queue();
                            return;
                        }
                    }
                    database.clearParkKillDeathCount(uid, id);
                    database.setParkSessionSlot(uid, id);
                    ParkedDinoPayload.Summary sum = ParkedDinoPayload.readSummaryFromStoredJson(r.payloadJson());
                    String raw = ParkedDinoPayload.readRawFromStoredJson(r.payloadJson()).orElse("(raw snapshot missing)");
                    BotConfig cfg = live.get();
                    PlayerdataFileRestore.Result fileRes = PlayerdataFileRestore.restore(
                            cfg.dinoParkPlayerdataFile(), cfg.configYamlPath(), r.steamId64(), r.payloadJson());
                    String head = "**Slot #" + id + "** (`" + truncate(r.label() == null ? "" : r.label(), 80) + "`)\n"
                            + "**SteamID64:** `" + r.steamId64() + "`\n"
                            + "**Saved (epoch sec):** " + r.parkedAtEpochSec() + "\n"
                            + "**Summary:** " + sum.oneLine() + "\n\n"
                            + "On-disk restore is controlled by **`dino_park.playerdata_file`** in `config.yml` "
                            + "(lossless capture + atomic write + `.bak`).\n\n"
                            + "**Stored `getplayerdata` text:**\n```\n";
                    String tail = "\n```" + (fileRes.discordLine().isEmpty() ? "" : fileRes.discordLine());
                    String full = head + truncate(raw, 1200) + tail;
                    hook.editOriginal(truncate(full, 2000)).queue(
                            ok -> {
                                if (cdSec <= 0) {
                                    return;
                                }
                                try {
                                    database.setParkRetrieveLastEpochSec(uid, id, Instant.now().getEpochSecond());
                                } catch (SQLException ex) {
                                    LOG.warn("dino retrieve cooldown stamp: {}", ex.toString());
                                }
                            },
                            err -> LOG.warn("hook.editOriginal (dino retrieve): {}", err.toString()));
                    LOG.info("dino retrieve: discordUserId={} steamId64={} slotId={} summary=\"{}\"",
                            uid, r.steamId64(), id, dinoSummaryForLog(sum.oneLine()));
                } catch (SQLException e) {
                    LOG.warn("dino retrieve: {}", e.toString());
                    hook.editOriginal("Database error.").queue();
                }
            }, f -> LOG.error("deferReply (dino retrieve) failed", f));
            return;
        }
        event.reply("Unknown dino subcommand.").setEphemeral(true).queue();
    }

    private static int yyyymmddUtc(LocalDate d) {
        return d.getYear() * 10_000 + d.getMonthValue() * 100 + d.getDayOfMonth();
    }

    private String randomCode(int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(ALPH.charAt(secureRandom.nextInt(ALPH.length())));
        }
        return sb.toString();
    }

    /**
     * If this SteamID64 is linked in {@code steam_links}, append a line with Discord username, mention, and id.
     */
    private void appendLinkedDiscordLineForSteam(StringBuilder sb, JDA jda, String steamId64) throws SQLException {
        Optional<String> linked = database.findDiscordForSteam(steamId64.trim());
        if (linked.isEmpty()) {
            sb.append("\n**Discord:** *(not linked in this bot)*");
            return;
        }
        String did = linked.get();
        try {
            long uid = Long.parseLong(did);
            User du = jda.retrieveUserById(uid).complete();
            sb.append("\n**Discord:** ").append(du.getName()).append(" — ").append(du.getAsMention()).append(" (`")
                    .append(did).append("`)");
        } catch (NumberFormatException e) {
            sb.append("\n**Discord:** stored link id `").append(did).append("` *(not a valid Discord snowflake)*");
        } catch (RuntimeException e) {
            LOG.debug("whois: retrieveUserById {} failed: {}", did, e.toString());
            sb.append("\n**Discord:** <@").append(did).append("> (`").append(did).append("`)");
        }
    }

    private static void appendIpLineFromWhoisPlayerdata(StringBuilder sb, String filteredBody) {
        Optional<String> ip = PlayerdataIpExtract.preferredIpv4(filteredBody);
        if (ip.isPresent()) {
            sb.append("\n**IP:** `").append(ip.get()).append("` *(from getplayerdata)*");
        } else {
            sb.append("\n**IP:** *(not present in getplayerdata text)*");
        }
    }

    /**
     * Reloads {@code config.yml}, {@code species-taxonomy.yml}, {@code kill-flavor.yml}, and {@code auto-messages.yml} — no process restart.
     */
    private void reloadYamlFromDisk() throws IOException {
        synchronized (live) {
            live.reloadFromDisk();
            population.reloadTaxonomyFromDisk();
            speciesControl.invalidateConfigCache();
            corpseWipe.onConfigReloaded();
        }
        autoMessageScheduler.restart();
    }

    private void appendFilteredGetplayerdataForWhois(StringBuilder sb, String steamId64) {
        try {
            String live = rcon.run("getplayerdata " + steamId64);
            String filtered = EvrimaRcon.filterGetplayerdataResponseForSteamId(live, steamId64);
            if (filtered.isEmpty()) {
                sb.append("\n\n*(No `getplayerdata` row for SteamID `").append(steamId64)
                        .append("` — usually not spawned in yet, or this build’s `PlayerID` differs from SteamID64.)*");
            } else {
                appendIpLineFromWhoisPlayerdata(sb, filtered);
                sb.append("\n\n`getplayerdata`:\n```\n")
                        .append(truncate(filtered, 1500)).append("\n```");
            }
        } catch (IOException e) {
            sb.append("\n\n*(RCON getplayerdata failed: ").append(e.getMessage()).append(")*");
        }
    }

    /**
     * SteamID64 is used as-is. Anything else is resolved against a fresh RCON {@code playerlist} (display name match).
     */
    private String resolveAdminPlayerTarget(String query) throws IOException {
        String q = query.strip();
        if (PlayerlistPopulationParser.isSteamId64(q)) {
            return q;
        }
        return PlayerTargetResolver.resolveToSteamOrThrow(q, rcon.run("playerlist"));
    }

    private static boolean steamIdLooksValid(String s) {
        if (s.length() < 17 || s.length() > 19) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static String jsonString(String s) {
        String esc = s.replace("\\", "\\\\").replace("\"", "\\\"");
        return "\"" + esc + "\"";
    }

    private static String requiredString(SlashCommandInteractionEvent event, String name) {
        OptionMapping m = event.getOption(name);
        if (m == null) {
            throw new IllegalStateException("Missing option: " + name);
        }
        return m.getAsString();
    }

    private static String optionalString(SlashCommandInteractionEvent event, String name, String def) {
        OptionMapping m = event.getOption(name);
        if (m == null) {
            return def;
        }
        String s = m.getAsString();
        return s == null || s.isBlank() ? def : s;
    }

    private static long requiredLong(SlashCommandInteractionEvent event, String name) {
        OptionMapping m = event.getOption(name);
        if (m == null) {
            throw new IllegalStateException("Missing option: " + name);
        }
        return m.getAsLong();
    }

    private static double requiredDouble(SlashCommandInteractionEvent event, String name) {
        OptionMapping m = event.getOption(name);
        if (m == null) {
            throw new IllegalStateException("Missing option: " + name);
        }
        return m.getAsDouble();
    }

    private static boolean optionalBoolean(SlashCommandInteractionEvent event, String name, boolean defaultVal) {
        OptionMapping m = event.getOption(name);
        return m == null ? defaultVal : m.getAsBoolean();
    }

    private static User requiredUser(SlashCommandInteractionEvent event, String name) {
        OptionMapping m = event.getOption(name);
        if (m == null) {
            throw new IllegalStateException("Missing option: " + name);
        }
        return m.getAsUser();
    }

    private static Member requiredMember(SlashCommandInteractionEvent event, String name) {
        OptionMapping m = event.getOption(name);
        if (m == null) {
            throw new IllegalStateException("Missing option: " + name);
        }
        Member mem = m.getAsMember();
        if (mem == null) {
            throw new IllegalStateException("Member not resolved — use this command inside the guild.");
        }
        return mem;
    }

    /**
     * {@link ParkedDinoPayload.Summary#oneLine()} uses a Unicode em dash when the RCON summary is empty; some Windows consoles misdecode it in log output.
     */
    private static String dinoSummaryForLog(String oneLine) {
        if (oneLine == null || oneLine.isBlank()) {
            return "(no rcon summary)";
        }
        if ("\u2014".equals(oneLine)) {
            return "(no rcon summary)";
        }
        return oneLine;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        if (s.length() <= max) {
            return s;
        }
        return s.substring(0, max - 20) + "\n...(truncated)";
    }
}
