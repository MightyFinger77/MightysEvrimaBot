package com.isle.evrima.bot.discord;

import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandGroupData;

import java.util.List;

/**
 * Slash commands are split across roots so server owners can hide whole trees per role in
 * Discord: Server Settings → Integrations → [this bot] → Manage (Command Permissions v2).
 */
public final class CommandRegistry {

    private CommandRegistry() {}

    public static List<CommandData> allCommands() {
        return List.of(
                publicEvrima(),
                modEvrima(),
                adminEvrima(),
                headEvrima()
        );
    }

    /** Everyone: linking, account, economy (balance/spin), dino slots. */
    private static CommandData publicEvrima() {
        return Commands.slash("evrima", "The Isle Evrima — linking, economy, parking (public)")
                .addSubcommandGroups(
                        new SubcommandGroupData("link", "Steam account linking")
                                .addSubcommands(
                                        new SubcommandData("start", "DMs you a code to finish linking"),
                                        new SubcommandData("complete", "Finish linking with the code from /evrima link start")
                                                .addOption(OptionType.STRING, "code", "Code from your DM", true)
                                                .addOption(OptionType.STRING, "steam_id", "Your SteamID64", true)
                                ),
                        new SubcommandGroupData("account", "Your account")
                                .addSubcommands(
                                        new SubcommandData("show", "Show your linked SteamID64"),
                                        new SubcommandData("debug", "Show your Discord role IDs vs bot config (troubleshooting)")
                                ),
                        new SubcommandGroupData("eco", "Economy")
                                .addSubcommands(
                                        new SubcommandData("balance", "Check your points"),
                                        new SubcommandData("spin", "Daily spin (once per UTC day)")
                                ),
                        new SubcommandGroupData("dino", "Parking slots (metadata)")
                                .addSubcommands(
                                        new SubcommandData("park", "Save a parking slot entry")
                                                .addOption(OptionType.STRING, "label", "Label shown in lists", false),
                                        new SubcommandData("list", "List your parking slots"),
                                        new SubcommandData("delete", "Delete a parking slot by id")
                                                .addOption(OptionType.INTEGER, "id", "Id from /evrima dino list", true),
                                        new SubcommandData("retrieve", "Placeholder until game restore exists")
                                                .addOption(OptionType.INTEGER, "id", "Id from /evrima dino list", true)
                                ),
                        new SubcommandGroupData("ecosystem", "Population dashboard (RCON playerlist + species taxonomy)")
                                .addSubcommands(
                                        new SubcommandData("dashboard", "Species counts, diet buckets, and percentages")
                                                .addOption(OptionType.BOOLEAN, "fresh",
                                                        "Bypass cache and query RCON again (default: use short-lived cache)", false)
                                )
                );
    }

    /** Staff: configure who sees this under Integrations → command permissions. */
    private static CommandData modEvrima() {
        return Commands.slash("evrima-mod", "Moderation — whois, Discord timeouts (bot checks config roles)")
                .addSubcommands(
                        new SubcommandData("whois", "Linked Steam + optional getplayerdata")
                                .addOption(OptionType.USER, "user", "Discord user", true),
                        new SubcommandData("timeout", "Discord timeout (not in-game)")
                                .addOption(OptionType.USER, "user", "Member to timeout", true)
                                .addOption(OptionType.INTEGER, "minutes", "1–40320 (28d max)", true)
                );
    }

    private static CommandData adminEvrima() {
        return Commands.slash("evrima-admin", "RCON admin + grant points (bot checks config admin/head_admin)")
                .addSubcommands(
                        new SubcommandData("announce", "In-game broadcast (RCON announce)")
                                .addOption(OptionType.STRING, "message", "Message text", true),
                        new SubcommandData("playerlist", "Fetch connected players (RCON playerlist; raw text)"),
                        new SubcommandData("kick", "Kick a player (RCON kick)")
                                .addOption(OptionType.STRING, "player", "SteamID64 or in-game name (from live playerlist)", true)
                                .addOption(OptionType.STRING, "reason", "Reason", true),
                        new SubcommandData("ban", "Ban a player (RCON ban; format is game-specific)")
                                .addOption(OptionType.STRING, "player", "SteamID64 or in-game name (from live playerlist)", true)
                                .addOption(OptionType.STRING, "reason", "Reason", true)
                                .addOption(OptionType.INTEGER, "minutes", "Ban minutes (0 = server default style)", false),
                        new SubcommandData("dm", "Private in-game message (RCON directmessage)")
                                .addOption(OptionType.STRING, "player", "SteamID64 or in-game name (from live playerlist)", true)
                                .addOption(OptionType.STRING, "message", "Message", true),
                        new SubcommandData("getplayer", "Dump player/server fields (RCON getplayerdata)")
                                .addOption(OptionType.STRING, "player", "SteamID64 or in-game name (from live playerlist)", true),
                        new SubcommandData("wipecorpses", "Remove corpses / cleanup bodies (RCON wipecorpses)"),
                        new SubcommandData("save", "Tell the game to save (RCON save)"),
                        new SubcommandData("unlink", "Remove this bot’s stored Steam link for a Discord user (not in-game)")
                                .addOption(OptionType.USER, "user", "Discord user", true),
                        new SubcommandData("give", "Add economy points (Discord bot DB only; not RCON)")
                                .addOption(OptionType.USER, "user", "Discord user", true)
                                .addOption(OptionType.INTEGER, "amount", "Points to add (0–1000000000)", true),
                        new SubcommandData("ai-toggle", "Flip the AI master switch (RCON toggleai — On↔Off; run again to undo)"),
                        new SubcommandData("ai-density", "Set AI spawn density multiplier (RCON aidensity; 0 = no new spawns)")
                                .addOption(OptionType.NUMBER, "value", "Multiplier (e.g. 0–1; see your host docs)", true),
                        new SubcommandData("ai-classes", "Disable AI creature types — NOT a toggle (RCON disableaiclasses)")
                                .addOption(OptionType.STRING, "classes", "Internal names, comma-separated (e.g. boar,Compsognathus)", true),
                        new SubcommandData("ai-stop-spawns", "Stop **new** AI spawns only: RCON aidensity 0 (use wipecorpses / ai-toggle separately)"),
                        new SubcommandData("ai-wipe", "Info: Evrima RCON has no opcode for admin-panel Wipe AI (no custom exec)"),
                        new SubcommandData("ai-learning", "Flip AI learning flag if your build supports it (RCON toggleailearning)"),
                        new SubcommandData("species-control", "Toggle dynamic species cap control scheduler (on/off/status)")
                                .addOption(OptionType.STRING, "mode", "on, off, or status", true),
                        new SubcommandData("species-cap-set", "Set runtime species cap (0 = unlimited/unmanaged)")
                                .addOption(OptionType.STRING, "species", "Species display name", true)
                                .addOption(OptionType.INTEGER, "cap", "Cap value (0..500)", true),
                        new SubcommandData("species-cap-clear", "Clear runtime override for one species (revert to config)")
                                .addOption(OptionType.STRING, "species", "Species display name", true),
                        new SubcommandData("species-cap-list", "Show effective species caps and runtime overrides"),
                        new SubcommandData("corpse-wipe-control", "Toggle scheduled corpse wipes (on/off/dynamic/status)")
                                .addOption(OptionType.STRING, "mode", "on, off, dynamic, or status", true),
                        new SubcommandData("corpse-wipe-set", "Set scheduled wipe runtime value")
                                .addOption(OptionType.STRING, "key",
                                        "interval_minutes, warn_before_minutes, announce_message, dynamic_*", true)
                                .addOption(OptionType.STRING, "value", "Value for the key", true),
                        new SubcommandData("corpse-wipe-clear", "Clear scheduled wipe runtime override(s)")
                                .addOption(OptionType.STRING, "key",
                                        "enabled, interval_minutes, warn_before_minutes, announce_message, dynamic_*, or all", true)
                );
    }

    private static CommandData headEvrima() {
        return Commands.slash("evrima-head", "Head admin only (configure visibility in Integrations)")
                .addSubcommands(
                        new SubcommandData("check", "Verify head-admin tier (placeholder for future server tools)")
                );
    }
}
