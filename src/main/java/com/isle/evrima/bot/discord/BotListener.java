package com.isle.evrima.bot.discord;

import com.isle.evrima.bot.config.BotConfig;
import com.isle.evrima.bot.db.Database;
import com.isle.evrima.bot.ecosystem.EcosystemEmbeds;
import com.isle.evrima.bot.ecosystem.PlayerlistPopulationParser;
import com.isle.evrima.bot.ecosystem.PlayerTargetResolver;
import com.isle.evrima.bot.ecosystem.PopulationDashboardService;
import com.isle.evrima.bot.ecosystem.SpeciesTaxonomy;
import com.isle.evrima.bot.rcon.EvrimaRcon;
import com.isle.evrima.bot.rcon.RconService;
import com.isle.evrima.bot.schedule.ScheduledCorpseWipeScheduler;
import com.isle.evrima.bot.schedule.SpeciesPopulationControlScheduler;
import com.isle.evrima.bot.security.PermissionService;
import com.isle.evrima.bot.security.StaffTier;
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
import java.security.SecureRandom;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

public final class BotListener extends ListenerAdapter {

    private static final Logger LOG = LoggerFactory.getLogger(BotListener.class);
    private static final String ALPH = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final String KV_SPECIES_CONTROL_ENABLED = "species_population_control_runtime_enabled";
    private static final String KV_SPECIES_CAP_OVERRIDE_PREFIX = "species_population_control_cap_override:";
    private static final String KV_CORPSE_WIPE_ENABLED = "scheduled_wipecorpses_runtime_enabled";
    private static final String KV_CORPSE_WIPE_INTERVAL = "scheduled_wipecorpses_runtime_interval_minutes";
    private static final String KV_CORPSE_WIPE_WARN = "scheduled_wipecorpses_runtime_warn_before_minutes";
    private static final String KV_CORPSE_WIPE_MESSAGE = "scheduled_wipecorpses_runtime_announce_message";
    private static final String KV_CORPSE_WIPE_DYN_MAX = "scheduled_wipecorpses_runtime_dynamic_max_players";
    private static final String KV_CORPSE_WIPE_DYN_PCT = "scheduled_wipecorpses_runtime_dynamic_enable_percent";
    private static final String KV_CORPSE_WIPE_DYN_GRACE = "scheduled_wipecorpses_runtime_dynamic_disable_grace_seconds";

    private final BotConfig config;
    private final Database database;
    private final RconService rcon;
    private final PermissionService permissions;
    private final PopulationDashboardService population;
    private final SpeciesPopulationControlScheduler speciesControl;
    private final ScheduledCorpseWipeScheduler corpseWipe;
    private final SecureRandom secureRandom = new SecureRandom();

    public BotListener(
            BotConfig config,
            Database database,
            RconService rcon,
            PermissionService permissions,
            PopulationDashboardService population,
            SpeciesPopulationControlScheduler speciesControl,
            ScheduledCorpseWipeScheduler corpseWipe
    ) {
        this.config = config;
        this.database = database;
        this.rcon = rcon;
        this.permissions = permissions;
        this.population = population;
        this.speciesControl = speciesControl;
        this.corpseWipe = corpseWipe;
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

    private void dispatchEvrima(SlashCommandInteractionEvent event) throws SQLException {
        String group = event.getSubcommandGroup();
        String sub = event.getSubcommandName();
        if (group == null || sub == null) {
            event.reply("Unknown command shape.").setEphemeral(true).queue();
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
                    long exp = java.time.Instant.now().getEpochSecond() + config.linkCodeTtlMinutes() * 60L;
                    database.putLinkCode(code, uid, exp);
                    User user = event.getUser();
                    user.openPrivateChannel().queue(
                            pc -> pc.sendMessage(
                                    "Your Isle linking code: **" + code + "**\nIt expires in "
                                            + config.linkCodeTtlMinutes()
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
                                database.appendAudit(event.getUser().getId(), "rcon_getplayer", steam);
                                hookEditEphemeral(hook, "```\n" + out + "\n```");
                            }
                            case "wipecorpses" -> {
                                String out = rcon.run("wipecorpses");
                                database.appendAudit(event.getUser().getId(), "rcon_wipecorpses", "");
                                hookEditEphemeral(hook, "RCON: " + out);
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
                                database.appendAudit(event.getUser().getId(), "rcon_toggleai", "");
                                hookEditEphemeral(hook,
                                        "**What this does:** `toggleai` **flips** the server’s AI master switch (On→Off or Off→On). "
                                                + "It does **not** target a species and does **not** delete existing AI dinos.\n"
                                                + "Run it again to flip back.\n\n"
                                                + "```\n" + truncate(out, 1700) + "\n```");
                            }
                            case "ai-density" -> {
                                double v = requiredDouble(event, "value");
                                String out = rcon.run(EvrimaRcon.lineAidensity(v));
                                database.appendAudit(event.getUser().getId(), "rcon_aidensity", String.valueOf(v));
                                hookEditEphemeral(hook,
                                        "**What this does:** `aidensity` sets how strongly **new** AI can spawn (multiplier; "
                                                + "`0` usually means no new AI spawns). It does **not** mass-kill AI already in the world.\n\n"
                                                + "```\n" + truncate(out, 1700) + "\n```");
                            }
                            case "ai-classes" -> {
                                String classes = requiredString(event, "classes").trim();
                                String out = rcon.run("disableaiclasses " + classes.replace('\n', ' '));
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
                                StringBuilder acc = new StringBuilder();
                                acc.append("**`/evrima-admin ai-stop-spawns`** runs **`aidensity 0`** only — it reduces or stops **new** AI spawns.\n");
                                acc.append("**It does not:** delete **living** AI (use **Insert → Admin → Wipe AI** in Evrima), "
                                        + "clean corpses (`wipecorpses`), or flip the global AI switch (`ai-toggle`).\n\n");
                                acc.append("**aidensity 0**\n```\n")
                                        .append(truncate(rcon.run(EvrimaRcon.lineAidensity(0)), 1200))
                                        .append("\n```\n");
                                acc.append("\n**Restore:** `/evrima-admin ai-density` with your usual multiplier.");
                                database.appendAudit(uid, "rcon_ai_stop_spawns", "");
                                hookEditEphemeral(hook, acc.toString());
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
                                    speciesControl.setEnabled(true);
                                    database.putBotKv(KV_SPECIES_CONTROL_ENABLED, "true");
                                    database.appendAudit(event.getUser().getId(), "species_control_toggle", "on");
                                    hookEditEphemeral(hook, "Species population control: **ON**");
                                    break;
                                }
                                if ("off".equals(mode) || "disable".equals(mode)) {
                                    speciesControl.setEnabled(false);
                                    database.putBotKv(KV_SPECIES_CONTROL_ENABLED, "false");
                                    database.appendAudit(event.getUser().getId(), "species_control_toggle", "off");
                                    hookEditEphemeral(hook, "Species population control: **OFF**");
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
                                speciesControl.setCapOverride(species, cap);
                                database.putBotKv(KV_SPECIES_CAP_OVERRIDE_PREFIX + species.toLowerCase(Locale.ROOT), String.valueOf(cap));
                                database.appendAudit(event.getUser().getId(), "species_cap_set", species + "=" + cap);
                                hookEditEphemeral(hook, "Set runtime species cap: **" + species + " = " + cap + "**");
                            }
                            case "species-cap-clear" -> {
                                String species = requiredString(event, "species").trim();
                                speciesControl.clearCapOverride(species);
                                database.deleteBotKv(KV_SPECIES_CAP_OVERRIDE_PREFIX + species.toLowerCase(Locale.ROOT));
                                database.appendAudit(event.getUser().getId(), "species_cap_clear", species);
                                hookEditEphemeral(hook, "Cleared runtime species cap override for **" + species + "**.");
                            }
                            case "species-cap-list" -> hookEditEphemeral(hook, speciesCapListText());
                            case "corpse-wipe-control" -> {
                                String mode = requiredString(event, "mode").trim().toLowerCase(Locale.ROOT);
                                if ("status".equals(mode)) {
                                    hookEditEphemeral(hook, corpseWipeStatusText());
                                    break;
                                }
                                if ("on".equals(mode) || "enable".equals(mode) || "true".equals(mode)) {
                                    corpseWipe.setEnabledMode("true");
                                    database.putBotKv(KV_CORPSE_WIPE_ENABLED, "true");
                                    database.appendAudit(event.getUser().getId(), "corpse_wipe_toggle", "on");
                                    hookEditEphemeral(hook, "Scheduled corpse wipes: **ON**");
                                    break;
                                }
                                if ("off".equals(mode) || "disable".equals(mode) || "false".equals(mode)) {
                                    corpseWipe.setEnabledMode("false");
                                    database.putBotKv(KV_CORPSE_WIPE_ENABLED, "false");
                                    database.appendAudit(event.getUser().getId(), "corpse_wipe_toggle", "off");
                                    hookEditEphemeral(hook, "Scheduled corpse wipes: **OFF**");
                                    break;
                                }
                                if ("dynamic".equals(mode)) {
                                    corpseWipe.setEnabledMode("dynamic");
                                    database.putBotKv(KV_CORPSE_WIPE_ENABLED, "dynamic");
                                    database.appendAudit(event.getUser().getId(), "corpse_wipe_toggle", "dynamic");
                                    hookEditEphemeral(hook, "Scheduled corpse wipes: **DYNAMIC**");
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
                                        corpseWipe.setIntervalMinutes(v);
                                        database.putBotKv(KV_CORPSE_WIPE_INTERVAL, String.valueOf(v));
                                        database.appendAudit(event.getUser().getId(), "corpse_wipe_set", "interval_minutes=" + v);
                                        hookEditEphemeral(hook, "Set scheduled_wipecorpses.interval_minutes = **" + v + "**");
                                    }
                                    case "warn", "warn_before", "warn_before_minutes" -> {
                                        int v = parseIntInRange(value, 0, 1_440, "warn_before_minutes");
                                        corpseWipe.setWarnBeforeMinutes(v);
                                        database.putBotKv(KV_CORPSE_WIPE_WARN, String.valueOf(v));
                                        database.appendAudit(event.getUser().getId(), "corpse_wipe_set", "warn_before_minutes=" + v);
                                        hookEditEphemeral(hook, "Set scheduled_wipecorpses.warn_before_minutes = **" + v + "**");
                                    }
                                    case "message", "announce", "announce_message" -> {
                                        corpseWipe.setAnnounceMessage(value);
                                        database.putBotKv(KV_CORPSE_WIPE_MESSAGE, value);
                                        database.appendAudit(event.getUser().getId(), "corpse_wipe_set", "announce_message");
                                        hookEditEphemeral(hook, "Updated scheduled_wipecorpses.announce_message.");
                                    }
                                    case "dynamic_max_players" -> {
                                        int v = parseIntInRange(value, 0, 1000, "dynamic_max_players");
                                        corpseWipe.setDynamicMaxPlayers(v);
                                        database.putBotKv(KV_CORPSE_WIPE_DYN_MAX, String.valueOf(v));
                                        database.appendAudit(event.getUser().getId(), "corpse_wipe_set", "dynamic_max_players=" + v);
                                        hookEditEphemeral(hook, "Set scheduled_wipecorpses.dynamic_max_players = **" + v + "**");
                                    }
                                    case "dynamic_enable_percent" -> {
                                        int v = parseIntInRange(value, 0, 100, "dynamic_enable_percent");
                                        corpseWipe.setDynamicEnablePercent(v);
                                        database.putBotKv(KV_CORPSE_WIPE_DYN_PCT, String.valueOf(v));
                                        database.appendAudit(event.getUser().getId(), "corpse_wipe_set", "dynamic_enable_percent=" + v);
                                        hookEditEphemeral(hook, "Set scheduled_wipecorpses.dynamic_enable_percent = **" + v + "%**");
                                    }
                                    case "dynamic_disable_grace_seconds" -> {
                                        int v = parseIntInRange(value, 0, 600, "dynamic_disable_grace_seconds");
                                        corpseWipe.setDynamicDisableGraceSeconds(v);
                                        database.putBotKv(KV_CORPSE_WIPE_DYN_GRACE, String.valueOf(v));
                                        database.appendAudit(event.getUser().getId(), "corpse_wipe_set", "dynamic_disable_grace_seconds=" + v);
                                        hookEditEphemeral(hook, "Set scheduled_wipecorpses.dynamic_disable_grace_seconds = **" + v + "s**");
                                    }
                                    default -> hookEditEphemeral(hook, "Unknown key. Use `interval_minutes`, `warn_before_minutes`, `announce_message`, `dynamic_max_players`, `dynamic_enable_percent`, or `dynamic_disable_grace_seconds`.");
                                }
                            }
                            case "corpse-wipe-clear" -> {
                                String key = requiredString(event, "key").trim().toLowerCase(Locale.ROOT);
                                switch (key) {
                                    case "interval", "interval_minutes" -> {
                                        corpseWipe.setIntervalMinutes(config.scheduledWipecorpsesIntervalMinutes());
                                        database.deleteBotKv(KV_CORPSE_WIPE_INTERVAL);
                                        hookEditEphemeral(hook, "Cleared runtime override for interval_minutes (reverted to config).");
                                    }
                                    case "warn", "warn_before", "warn_before_minutes" -> {
                                        corpseWipe.setWarnBeforeMinutes(config.scheduledWipecorpsesWarnBeforeMinutes());
                                        database.deleteBotKv(KV_CORPSE_WIPE_WARN);
                                        hookEditEphemeral(hook, "Cleared runtime override for warn_before_minutes (reverted to config).");
                                    }
                                    case "message", "announce", "announce_message" -> {
                                        corpseWipe.setAnnounceMessage(config.scheduledWipecorpsesAnnounceMessage());
                                        database.deleteBotKv(KV_CORPSE_WIPE_MESSAGE);
                                        hookEditEphemeral(hook, "Cleared runtime override for announce_message (reverted to config).");
                                    }
                                    case "enabled" -> {
                                        corpseWipe.setEnabledMode(config.scheduledWipecorpsesEnabledMode());
                                        database.deleteBotKv(KV_CORPSE_WIPE_ENABLED);
                                        hookEditEphemeral(hook, "Cleared runtime override for enabled (reverted to config).");
                                    }
                                    case "dynamic_max_players" -> {
                                        corpseWipe.setDynamicMaxPlayers(config.scheduledWipecorpsesDynamicMaxPlayers());
                                        database.deleteBotKv(KV_CORPSE_WIPE_DYN_MAX);
                                        hookEditEphemeral(hook, "Cleared runtime override for dynamic_max_players (reverted to config).");
                                    }
                                    case "dynamic_enable_percent" -> {
                                        corpseWipe.setDynamicEnablePercent(config.scheduledWipecorpsesDynamicEnablePercent());
                                        database.deleteBotKv(KV_CORPSE_WIPE_DYN_PCT);
                                        hookEditEphemeral(hook, "Cleared runtime override for dynamic_enable_percent (reverted to config).");
                                    }
                                    case "dynamic_disable_grace_seconds" -> {
                                        corpseWipe.setDynamicDisableGraceSeconds(config.scheduledWipecorpsesDynamicDisableGraceSeconds());
                                        database.deleteBotKv(KV_CORPSE_WIPE_DYN_GRACE);
                                        hookEditEphemeral(hook, "Cleared runtime override for dynamic_disable_grace_seconds (reverted to config).");
                                    }
                                    case "all" -> {
                                        corpseWipe.setEnabledMode(config.scheduledWipecorpsesEnabledMode());
                                        corpseWipe.setIntervalMinutes(config.scheduledWipecorpsesIntervalMinutes());
                                        corpseWipe.setWarnBeforeMinutes(config.scheduledWipecorpsesWarnBeforeMinutes());
                                        corpseWipe.setAnnounceMessage(config.scheduledWipecorpsesAnnounceMessage());
                                        corpseWipe.setDynamicMaxPlayers(config.scheduledWipecorpsesDynamicMaxPlayers());
                                        corpseWipe.setDynamicEnablePercent(config.scheduledWipecorpsesDynamicEnablePercent());
                                        corpseWipe.setDynamicDisableGraceSeconds(config.scheduledWipecorpsesDynamicDisableGraceSeconds());
                                        database.deleteBotKv(KV_CORPSE_WIPE_ENABLED);
                                        database.deleteBotKv(KV_CORPSE_WIPE_INTERVAL);
                                        database.deleteBotKv(KV_CORPSE_WIPE_WARN);
                                        database.deleteBotKv(KV_CORPSE_WIPE_MESSAGE);
                                        database.deleteBotKv(KV_CORPSE_WIPE_DYN_MAX);
                                        database.deleteBotKv(KV_CORPSE_WIPE_DYN_PCT);
                                        database.deleteBotKv(KV_CORPSE_WIPE_DYN_GRACE);
                                        hookEditEphemeral(hook, "Cleared all scheduled_wipecorpses runtime overrides (reverted to config).");
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
                        LOG.warn("admin RCON: {}", e.toString());
                        hookEditEphemeral(hook, "RCON failed: " + truncate(e.getMessage(), 1800));
                    } catch (IllegalStateException e) {
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
        if ("whois".equals(sub)) {
            User target = requiredUser(event, "user");
            event.deferReply(true).queue(hook -> {
                try {
                    Optional<String> steam = database.findSteamIdForDiscord(target.getId());
                    StringBuilder sb = new StringBuilder();
                    sb.append("Discord: ").append(target.getName()).append(" (").append(target.getId()).append(")\n");
                    if (steam.isEmpty()) {
                        sb.append("Steam: *(not linked)*");
                    } else {
                        sb.append("SteamID64: `").append(steam.get()).append("`");
                        try {
                            String live = rcon.run("getplayerdata " + steam.get());
                            sb.append("\n\n`getplayerdata`:\n```\n").append(truncate(live, 1500)).append("\n```");
                        } catch (IOException e) {
                            sb.append("\n\n*(RCON getplayerdata failed: ").append(e.getMessage()).append(")*");
                        }
                    }
                    database.appendAudit(event.getUser().getId(), "mod_whois", target.getId());
                    hookEditEphemeral(hook, sb.toString());
                } catch (SQLException e) {
                    LOG.error("mod whois", e);
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
                MessageEmbed embed = EcosystemEmbeds.build(
                        config.ecosystemTitle(),
                        res.data(),
                        tax,
                        event.getGuild());
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
            int lo = Math.min(config.dailySpinMin(), config.dailySpinMax());
            int hi = Math.max(config.dailySpinMin(), config.dailySpinMax());
            int roll = ThreadLocalRandom.current().nextInt(lo, hi + 1);
            database.setLastSpinDay(uid, today);
            database.addBalance(uid, roll);
            database.appendAudit(uid, "eco_spin", String.valueOf(roll));
            event.reply("Daily spin: **+" + roll + "** points. New balance: **" + database.getBalance(uid) + "**.")
                    .setEphemeral(true).queue();
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

    private void hookEditEphemeral(InteractionHook hook, String text) {
        String t = truncate(text, 2000);
        if (t.isBlank()) {
            t = "(no output)";
        }
        hook.editOriginal(t).queue(null, err -> LOG.warn("hook.editOriginal failed: {}", err.toString()));
    }

    private String speciesControlStatusText() {
        String state = speciesControl.isEnabled() ? "ON" : "OFF";
        return "Species population control: **" + state + "**\n"
                + "Configured caps: **" + speciesControl.effectiveCapsReadOnly().size() + "** species\n"
                + "Interval: **" + config.speciesPopulationControlIntervalSeconds() + "s**\n"
                + "Unlock offset: **" + config.speciesPopulationControlUnlockBelowOffset() + "**\n"
                + "Announce changes: **" + config.speciesPopulationControlAnnounceChanges() + "**";
    }

    private String speciesCapListText() {
        Map<String, Integer> effective = speciesControl.effectiveCapsReadOnly();
        Map<String, Integer> overrides = speciesControl.listCapOverrides();
        StringBuilder sb = new StringBuilder();
        sb.append("**Species caps (effective):**\n");
        for (Map.Entry<String, Integer> e : effective.entrySet()) {
            String mark = overrides.containsKey(e.getKey()) ? " _(override)_" : "";
            sb.append("- ").append(e.getKey()).append(": **").append(e.getValue()).append("**").append(mark).append("\n");
        }
        if (overrides.isEmpty()) {
            sb.append("\nNo runtime overrides.");
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
            String json = "{\"v\":1,\"label\":" + jsonString(label)
                    + ",\"steam\":" + jsonString(steam.get())
                    + ",\"note\":\"Metadata-only until you wire real dino state from logs/API.\"}";
            long id = database.insertParkedDino(uid, steam.get(), label, json);
            database.appendAudit(uid, "dino_park", String.valueOf(id));
            event.reply("Saved parking slot **#" + id + "** (`" + truncate(label, 80) + "`). "
                    + "Restore is not automated yet — this row is for your own workflows.").setEphemeral(true).queue();
            return;
        }
        if ("list".equals(sub)) {
            var rows = database.listParked(uid);
            if (rows.isEmpty()) {
                event.reply("No parking slots saved.").setEphemeral(true).queue();
                return;
            }
            StringBuilder sb = new StringBuilder();
            for (Database.ParkedRow r : rows) {
                sb.append("#").append(r.id()).append(" — ").append(r.label() == null ? "(no label)" : r.label())
                        .append(" — parked ").append(r.parkedAtEpochSec()).append("\n");
            }
            event.reply(truncate(sb.toString(), 2000)).setEphemeral(true).queue();
            return;
        }
        if ("delete".equals(sub)) {
            long id = requiredLong(event, "id");
            if (database.deleteParked(id, uid)) {
                database.appendAudit(uid, "dino_delete", String.valueOf(id));
                event.reply("Deleted slot **#" + id + "**.").setEphemeral(true).queue();
            } else {
                event.reply("No slot **#" + id + "** for your account.").setEphemeral(true).queue();
            }
            return;
        }
        if ("retrieve".equals(sub)) {
            long id = requiredLong(event, "id");
            event.reply("Slot **#" + id + "**: in-game restore is **not implemented** in this bot yet "
                    + "(needs supported server API or your own capture pipeline). "
                    + "Use stored metadata from your DB export or extend `parked_dinos.payload_json`.")
                    .setEphemeral(true).queue();
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
