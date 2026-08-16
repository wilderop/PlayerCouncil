package com.lawlessmc.playercouncil.managers;

import com.lawlessmc.playercouncil.PlayerCouncilPlugin;
import com.lawlessmc.playercouncil.models.Proposal;
import com.lawlessmc.playercouncil.storage.DatabaseManager.TrackedBan;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.BanList;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.Date;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Automated ban-review prompts for council members on join.
 * Reaffirm / Overturn clickables; overturn creates or joins a pardon proposal.
 */
public class BanReviewManager {

    private final PlayerCouncilPlugin plugin;
    private final MiniMessage mm = MiniMessage.miniMessage();

    public BanReviewManager(PlayerCouncilPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean isEnabled() {
        return plugin.getConfig().getBoolean("ban-review.enabled", true);
    }

    private int maxPrompts() {
        return plugin.getConfig().getInt("ban-review.max-prompts-per-join", 5);
    }

    private long lookbackMs() {
        int days = plugin.getConfig().getInt("ban-review.lookback-days", 7);
        return TimeUnit.DAYS.toMillis(Math.max(1, days));
    }

    private long expireMs() {
        int days = plugin.getConfig().getInt("ban-review.review-expire-days", 7);
        return TimeUnit.DAYS.toMillis(Math.max(1, days));
    }

    /** Record a ban from any source (council, smartban, vanilla). Dedupes active rows. */
    public void recordBan(UUID targetUuid, String targetName, String reason, String source,
                          UUID bannedByUuid, String bannedByName) {
        if (targetUuid == null) return;
        long now = System.currentTimeMillis();
        plugin.getDatabaseManager().recordTrackedBanAsync(
                targetUuid,
                targetName != null ? targetName : targetUuid.toString(),
                reason,
                source != null ? source : "unknown",
                bannedByUuid,
                bannedByName,
                now
        );
    }

    public void recordUnban(UUID targetUuid, String targetName) {
        if (targetUuid != null) {
            plugin.getDatabaseManager().markTrackedBanInactive(targetUuid);
        } else if (targetName != null) {
            plugin.getDatabaseManager().markTrackedBanInactiveByName(targetName);
        }
    }

    /**
     * Import recent entries from the vanilla name ban list that we do not track yet.
     * Safe to call occasionally (e.g. on council join).
     */
    public void syncFromVanillaBanList() {
        long after = System.currentTimeMillis() - lookbackMs();
        try {
            @SuppressWarnings("deprecation")
            var entries = Bukkit.getBanList(BanList.Type.NAME).getEntries();
            for (var entry : entries) {
                if (entry == null) continue;
                String target = entry.getTarget();
                if (target == null || target.isBlank()) continue;
                Date created = entry.getCreated();
                long bannedAt = created != null ? created.getTime() : System.currentTimeMillis();
                if (bannedAt < after) continue;
                OfflinePlayer off = Bukkit.getOfflinePlayer(target);
                UUID uuid = off.getUniqueId();
                String reason = entry.getReason();
                String sourceStaff = entry.getSource();
                plugin.getDatabaseManager().recordTrackedBanAsync(
                        uuid, target, reason, "vanilla",
                        null, sourceStaff != null ? sourceStaff : "unknown", bannedAt);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Ban list sync failed: " + e.getMessage());
        }
    }

    /** Called when a council member joins (main thread or scheduled). */
    public void onCouncilMemberJoin(Player player) {
        if (!isEnabled()) return;
        if (!plugin.getCouncilManager().isCouncilMember(player.getUniqueId())) return;
        if (!plugin.getCouncilManager().isSystemActive()) return;

        // Pull any recent vanilla bans we might have missed
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            syncFromVanillaBanList();
            plugin.getDatabaseManager()
                    .getPendingReviewsForCouncilAsync(
                            player.getUniqueId(), lookbackMs(), expireMs(), maxPrompts())
                    .thenAccept(list -> Bukkit.getScheduler().runTask(plugin, () -> {
                        if (!player.isOnline()) return;
                        if (list == null || list.isEmpty()) return;

                        long now = System.currentTimeMillis();
                        int shown = 0;
                        for (TrackedBan ban : list) {
                            // Skip if this member was the original proposer
                            if (ban.bannedByUuid() != null
                                    && ban.bannedByUuid().equals(player.getUniqueId())) {
                                continue;
                            }
                            if (ban.firstPromptedAt() == null) {
                                plugin.getDatabaseManager().setBanFirstPrompted(ban.id(), now);
                            }
                            sendPrompt(player, ban);
                            shown++;
                            if (shown >= maxPrompts()) break;
                        }
                        if (shown > 0) {
                            player.sendMessage(mm.deserialize(
                                    "<gray>[<gold>Council</gold>]</gray> <gray>Review "
                                            + shown + " recent ban(s). Click Reaffirm or Overturn."));
                        }
                    }));
        });
    }

    private void sendPrompt(Player player, TrackedBan ban) {
        String reason = ban.reason() != null && !ban.reason().isBlank()
                ? ban.reason() : "(no reason recorded)";
        String by = ban.bannedByName() != null && !ban.bannedByName().isBlank()
                ? ban.bannedByName() : "unknown / staff";
        String source = ban.source() != null ? ban.source() : "unknown";

        player.sendMessage(mm.deserialize(
                "<gray>[<gold>Council</gold>]</gray> <yellow>Recent ban:</yellow> <white>"
                        + ban.targetName() + "</white>"));
        player.sendMessage(mm.deserialize(
                "  <gray>Reason:</gray> <white>" + escapeMini(reason) + "</white>"));
        player.sendMessage(mm.deserialize(
                "  <gray>By:</gray> <white>" + escapeMini(by)
                        + "</white> <dark_gray>(" + source + ")</dark_gray>"));

        Component reaffirm = Component.text("[Reaffirm]")
                .color(NamedTextColor.GREEN)
                .decorate(TextDecoration.BOLD)
                .hoverEvent(HoverEvent.showText(Component.text(
                        "Keep this ban. If a pardon vote is open, casts NO.")))
                .clickEvent(ClickEvent.runCommand("/councilreview " + ban.id() + " reaffirm"));

        Component overturn = Component.text("[Overturn]")
                .color(NamedTextColor.RED)
                .decorate(TextDecoration.BOLD)
                .hoverEvent(HoverEvent.showText(Component.text(
                        "Start or support a pardon proposal for this player.")))
                .clickEvent(ClickEvent.runCommand("/councilreview " + ban.id() + " overturn"));

        player.sendMessage(Component.text("  ")
                .append(reaffirm)
                .append(Component.text("  "))
                .append(overturn));
    }

    private static String escapeMini(String s) {
        if (s == null) return "";
        return s.replace("<", "").replace(">", "");
    }

    public void handleResponse(Player player, int banId, String action) {
        if (!isEnabled()) {
            player.sendMessage(mm.deserialize("<red>Ban review is disabled."));
            return;
        }
        if (!plugin.getCouncilManager().isCouncilMember(player.getUniqueId())) {
            player.sendMessage(mm.deserialize("<red>Only council members can review bans."));
            return;
        }

        String act = action == null ? "" : action.trim().toLowerCase();
        if (!act.equals("reaffirm") && !act.equals("overturn")) {
            player.sendMessage(mm.deserialize("<red>Usage: /councilreview <id> reaffirm|overturn"));
            return;
        }

        plugin.getDatabaseManager().getTrackedBanAsync(banId).thenAccept(ban -> {
            if (ban == null || !ban.active()) {
                Bukkit.getScheduler().runTask(plugin, () ->
                        player.sendMessage(mm.deserialize("<red>Ban review not found or already resolved.")));
                return;
            }
            // Expiry check
            if (ban.firstPromptedAt() != null
                    && ban.firstPromptedAt() + expireMs() < System.currentTimeMillis()) {
                Bukkit.getScheduler().runTask(plugin, () ->
                        player.sendMessage(mm.deserialize("<red>This ban review has expired.")));
                return;
            }
            if (ban.bannedByUuid() != null && ban.bannedByUuid().equals(player.getUniqueId())) {
                Bukkit.getScheduler().runTask(plugin, () ->
                        player.sendMessage(mm.deserialize(
                                "<red>You proposed this ban — you cannot review it.")));
                return;
            }

            plugin.getDatabaseManager().hasBanReviewResponseAsync(banId, player.getUniqueId())
                    .thenAccept(already -> {
                        if (already) {
                            Bukkit.getScheduler().runTask(plugin, () ->
                                    player.sendMessage(mm.deserialize(
                                            "<red>You already responded to this ban review.")));
                            return;
                        }
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            if (act.equals("reaffirm")) {
                                doReaffirm(player, ban);
                            } else {
                                doOverturn(player, ban);
                            }
                        });
                    });
        });
    }

    private void doReaffirm(Player player, TrackedBan ban) {
        plugin.getDatabaseManager().saveBanReviewResponse(ban.id(), player.getUniqueId(), "REAFFIRM");
        plugin.getDatabaseManager().log(player.getName() + " reaffirmed ban of " + ban.targetName()
                + " (tracked #" + ban.id() + ")");

        plugin.getDatabaseManager().findActivePardonForTargetAsync(ban.targetName())
                .thenAccept(pardon -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (pardon != null && pardon.isActive()) {
                        plugin.getProposalManager().voteAllowChange(player, pardon.getId(), false);
                        player.sendMessage(mm.deserialize(
                                "<green>Reaffirmed.</green> <gray>Voted <red>NO</red> on open pardon #"
                                        + pardon.getId() + "."));
                    } else {
                        player.sendMessage(mm.deserialize(
                                "<green>Reaffirmed.</green> <gray>No open pardon — ban stands for your review."));
                    }
                }));
    }

    private void doOverturn(Player player, TrackedBan ban) {
        plugin.getDatabaseManager().saveBanReviewResponse(ban.id(), player.getUniqueId(), "OVERTURN");
        plugin.getDatabaseManager().log(player.getName() + " sought overturn of ban of " + ban.targetName()
                + " (tracked #" + ban.id() + ")");

        plugin.getDatabaseManager().findActivePardonForTargetAsync(ban.targetName())
                .thenAccept(existing -> {
                    if (existing != null && existing.isActive()) {
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            plugin.getProposalManager().voteAllowChange(player, existing.getId(), true);
                            player.sendMessage(mm.deserialize(
                                    "<green>Voted <white>YES</white> on open pardon #"
                                            + existing.getId() + "."));
                        });
                        return;
                    }
                    // Create new pardon via ladder (or forced PARDON for external bans)
                    plugin.getBanVoteManager().resolveLadder(ban.targetName(), false)
                            .thenAccept(res -> Bukkit.getScheduler().runTask(plugin, () -> {
                                Proposal.Type type;
                                int votes;
                                if (res.type() == null) {
                                    // No council ladder stage — still allow a standard pardon
                                    type = Proposal.Type.PARDON;
                                    votes = plugin.getConfig().getInt("voting.pardon", 2);
                                } else {
                                    type = res.type();
                                    votes = res.requiredVotes();
                                }
                                String reason = "Council review overturn of: "
                                        + (ban.reason() != null ? ban.reason() : "ban #" + ban.id());
                                plugin.getProposalManager().createProposal(
                                        player, type, ban.targetName(),
                                        String.valueOf(votes), reason);
                                // Proposer auto-votes yes once the proposal exists (short delay for async insert)
                                Bukkit.getScheduler().runTaskLater(plugin, () ->
                                        plugin.getDatabaseManager()
                                                .findActivePardonForTargetAsync(ban.targetName())
                                                .thenAccept(p -> {
                                                    if (p != null && p.isActive()) {
                                                        Bukkit.getScheduler().runTask(plugin, () ->
                                                                plugin.getProposalManager()
                                                                        .voteAllowChange(player, p.getId(), true));
                                                    }
                                                }), 20L);
                                player.sendMessage(mm.deserialize(
                                        "<green>Opened a " + type.name()
                                                + " proposal for <white>" + ban.targetName()
                                                + "</white> (needs " + votes + " yes votes)."));
                            }));
                });
    }
}
