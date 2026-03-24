package com.isle.evrima.bot.security;

import com.isle.evrima.bot.config.BotConfig;
import com.isle.evrima.bot.config.LiveBotConfig;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public final class PermissionService {

    private final LiveBotConfig live;

    public PermissionService(LiveBotConfig live) {
        this.live = live;
    }

    private BotConfig cfg() {
        return live.get();
    }

    public int moderatorRoleCount() {
        return cfg().moderatorRoleIds().size();
    }

    public int adminRoleCount() {
        return cfg().adminRoleIds().size();
    }

    public int headAdminRoleCount() {
        return cfg().headAdminRoleIds().size();
    }

    public boolean anyModeratorRolesConfigured() {
        return !cfg().moderatorRoleIds().isEmpty();
    }

    public boolean anyAdminOrHeadRolesConfigured() {
        return !cfg().adminRoleIds().isEmpty() || !cfg().headAdminRoleIds().isEmpty();
    }

    /**
     * Role IDs on the member that appear in any staff list (for troubleshooting).
     */
    public List<Long> matchingConfiguredRoleIds(Member member) {
        List<Long> hit = new ArrayList<>();
        if (member == null) {
            return hit;
        }
        Set<Long> moderatorRoles = new HashSet<>(cfg().moderatorRoleIds());
        Set<Long> adminRoles = new HashSet<>(cfg().adminRoleIds());
        Set<Long> headAdminRoles = new HashSet<>(cfg().headAdminRoleIds());
        for (Role r : member.getRoles()) {
            long id = r.getIdLong();
            if (moderatorRoles.contains(id) || adminRoles.contains(id) || headAdminRoles.contains(id)) {
                hit.add(id);
            }
        }
        return hit;
    }

    public String formatConfiguredRoleSummary() {
        return "config `discord.roles`: moderator=" + moderatorRoleCount() + " id(s), admin="
                + adminRoleCount() + " id(s), head_admin=" + headAdminRoleCount() + " id(s)";
    }

    public String formatMemberRoleIds(Member member) {
        if (member == null) {
            return "(no member)";
        }
        return member.getRoles().stream().map(r -> String.valueOf(r.getIdLong())).collect(Collectors.joining(", "));
    }

    public String denyAdminMessage(Member member) {
        if (!anyAdminOrHeadRolesConfigured()) {
            return "No **admin** or **head_admin** role IDs are set in `config.yml`. "
                    + "Add your staff role IDs under `discord.roles.admin` and/or `discord.roles.head_admin`, restart the bot, "
                    + "then assign that role to yourself in this Discord server.";
        }
        StaffTier t = tier(member);
        List<Long> matches = matchingConfiguredRoleIds(member);
        String base = "You need **admin** access: assign yourself a role listed under `discord.roles.admin` "
                + "**or** `discord.roles.head_admin` in this server. "
                + "Your effective tier right now: **" + t + "**.";
        if (matches.isEmpty()) {
            return base + "\n\nNone of your current roles match those lists. Your role IDs here: "
                    + formatMemberRoleIds(member) + "\n" + formatConfiguredRoleSummary()
                    + "\n\nUse `/evrima account debug` to compare IDs.";
        }
        return base + "\n\n(Matched configured staff role id(s): " + matches + " — if you still see this, "
                + "reload config or restart the bot after editing `config.yml`.)";
    }

    public String denyModeratorMessage(Member member) {
        if (!anyModeratorRolesConfigured() && !anyAdminOrHeadRolesConfigured()) {
            return "No **moderator**, **admin**, or **head_admin** role IDs are set in `config.yml`. "
                    + "Set at least `discord.roles.moderator` (or admin/head_admin), restart the bot, assign the role on Discord.";
        }
        StaffTier t = tier(member);
        return "You need **moderator** (or higher) access: a role under `discord.roles.moderator`, `admin`, or `head_admin`. "
                + "Your tier: **" + t + "**. Your role IDs: " + formatMemberRoleIds(member)
                + "\n" + formatConfiguredRoleSummary()
                + "\n`/evrima account debug` shows details.";
    }

    public String denyHeadAdminMessage(Member member) {
        if (cfg().headAdminRoleIds().isEmpty()) {
            return "No **head_admin** role IDs in `config.yml`. Add `discord.roles.head_admin`, restart, assign that role.";
        }
        return "You need **head_admin**: assign a role listed under `discord.roles.head_admin`. "
                + "Your tier: **" + tier(member) + "**. `/evrima account debug` for role IDs.";
    }

    public StaffTier tier(Member member) {
        if (member == null) {
            return StaffTier.PLAYER;
        }
        Set<Long> moderatorRoles = new HashSet<>(cfg().moderatorRoleIds());
        Set<Long> adminRoles = new HashSet<>(cfg().adminRoleIds());
        Set<Long> headAdminRoles = new HashSet<>(cfg().headAdminRoleIds());
        StaffTier best = StaffTier.PLAYER;
        for (Role r : member.getRoles()) {
            long id = r.getIdLong();
            if (headAdminRoles.contains(id)) {
                return StaffTier.HEAD_ADMIN;
            }
            if (adminRoles.contains(id)) {
                best = StaffTier.ADMIN;
            } else if (moderatorRoles.contains(id) && best.ordinal() < StaffTier.MODERATOR.ordinal()) {
                best = StaffTier.MODERATOR;
            }
        }
        return best;
    }

    public boolean isAtLeast(Member member, StaffTier required) {
        return tier(member).isAtLeast(required);
    }
}
