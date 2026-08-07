package com.lawlessmc.playercouncil.managers;

import com.lawlessmc.playercouncil.PlayerCouncilPlugin;
import com.lawlessmc.playercouncil.models.Proposal;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Automatic ban ladder + council-member protection + playtime-based first-ban threshold.
 *
 * Ladder stages (stored in DB):
 *   0 = clean            → BAN (1 vote if target &lt; veteran-hours, else 2)
 *   1 = council-banned   → UNBAN needs 2 votes (PARDON)
 *   2 = pardoned once    → BAN needs 4 votes (REBAN)
 *   3 = re-banned        → UNBAN needs 8 votes (REPARDON)
 * After a successful REPARDON, stage returns to 2 (further bans stay hard).
 *
 * Sitting council members — and any account that has ever shared an IP with a
 * sitting council member — always require the elevated council-ban threshold
 * (config voting.council-ban, default 8).
 */
public class BanVoteManager {

    private final PlayerCouncilPlugin plugin;

    public BanVoteManager(PlayerCouncilPlugin plugin) {
        this.plugin = plugin;
    }

    public record LadderResolution(
            Proposal.Type type,
            int requiredVotes,
            int currentStage,
            UUID targetUuid,
            String targetName,
            String explanation
    ) {}

    public OfflinePlayer resolveOffline(String name) {
        for (var p : Bukkit.getOnlinePlayers()) {
            if (p.getName().equalsIgnoreCase(name)) {
                return p;
            }
        }
        @SuppressWarnings("deprecation")
        OfflinePlayer off = Bukkit.getOfflinePlayer(name);
        return off;
    }

    public boolean isSittingCouncilMember(UUID uuid) {
        return plugin.getCouncilManager().isCouncilMember(uuid);
    }

    /** Elevated vote count used for sitting council members and their IP alts. */
    public int councilBanVotes() {
        return plugin.getConfig().getInt("voting.council-ban",
                plugin.getConfig().getInt("voting.repardon", 8));
    }

    /**
     * True if target is a sitting council member, or has ever shared an IP with one.
     * Uses recorded player_ips data (join history).
     */
    public CompletableFuture<Boolean> isCouncilOrCouncilAlt(UUID target) {
        if (isSittingCouncilMember(target)) {
            return CompletableFuture.completedFuture(true);
        }
        java.util.List<UUID> council = plugin.getCouncilManager().getCouncilMembers();
        if (council.isEmpty()) {
            return CompletableFuture.completedFuture(false);
        }
        java.util.List<UUID> candidates = new java.util.ArrayList<>(council);
        candidates.add(target);
        return plugin.getDatabaseManager().getIpRelatedGroupsAsync(candidates).thenApply(related -> {
            java.util.Set<UUID> group = related.getOrDefault(target, java.util.Set.of(target));
            for (UUID c : council) {
                if (group.contains(c)) return true;
            }
            return false;
        });
    }

    /** Total playtime hours from stored player meta (0 if unknown). */
    public long playtimeHours(long playTicks) {
        return Math.max(0L, playTicks / (20L * 3600L));
    }

    /**
     * First-ban vote threshold based on target total hours.
     * New players (&lt; veteran-hours): ban-new (default 1).
     * Veterans (≥ veteran-hours): ban-veteran (default 2).
     */
    public int firstBanVotesForHours(long hours) {
        int veteranHours = plugin.getConfig().getInt("voting.veteran-hours", 100);
        if (hours >= veteranHours) {
            return plugin.getConfig().getInt("voting.ban-veteran", 2);
        }
        return plugin.getConfig().getInt("voting.ban-new", 1);
    }

    /**
     * For requested action ban/unban, pick proposal type and vote threshold
     * from the player's ladder stage, playtime, and council/IP-alt status.
     */
    public CompletableFuture<LadderResolution> resolveLadder(String targetName, boolean wantBan) {
        OfflinePlayer off = resolveOffline(targetName);
        UUID uuid = off.getUniqueId();
        String name = off.getName() != null ? off.getName() : targetName;

        CompletableFuture<Integer> stageF = plugin.getDatabaseManager().getBanLadderStageAsync(uuid);
        CompletableFuture<Long> playF = plugin.getDatabaseManager().getTotalPlaytimeAsync(uuid);
        CompletableFuture<Boolean> protectedF = wantBan
                ? isCouncilOrCouncilAlt(uuid)
                : CompletableFuture.completedFuture(false);

        return CompletableFuture.allOf(stageF, playF, protectedF).thenApply(v -> {
            int stage = stageF.join();
            long playTicks = playF.join();
            boolean councilProtected = protectedF.join();
            long hours = playtimeHours(playTicks);

            if (wantBan) {
                boolean sittingCouncil = isSittingCouncilMember(uuid);
                if (stage <= 0) {
                    if (councilProtected) {
                        int votes = councilBanVotes();
                        String why = sittingCouncil
                                ? "Target is a sitting council member"
                                : "Target shares an IP with a sitting council member (alt protection)";
                        return new LadderResolution(
                                Proposal.Type.REBAN, votes, stage, uuid, name,
                                why + " — ban requires " + votes + " votes."
                        );
                    }
                    int votes = firstBanVotesForHours(hours);
                    String who = hours >= plugin.getConfig().getInt("voting.veteran-hours", 100)
                            ? "veteran (~" + hours + "h) — needs " + votes + " council votes"
                            : "new player (~" + hours + "h) — 1 council member can ban";
                    return new LadderResolution(
                            Proposal.Type.BAN, votes, stage, uuid, name,
                            "First council ban (" + who + ")."
                    );
                }
                // Already been through the ladder — still apply council/alt floor if higher
                int votes = plugin.getConfig().getInt("voting.reban", 4);
                if (councilProtected) {
                    votes = Math.max(votes, councilBanVotes());
                }
                String extra = councilProtected
                        ? (sittingCouncil
                            ? " (sitting council member)"
                            : " (shares IP with a sitting council member)")
                        : "";
                return new LadderResolution(
                        Proposal.Type.REBAN, votes, stage, uuid, name,
                        "Player was previously through the council process — re-ban requires "
                                + votes + " votes" + extra + "."
                );
            }

            // unban
            if (stage == 0) {
                return new LadderResolution(
                        null, 0, stage, uuid, name,
                        "Player has no council ban on record."
                );
            }
            if (stage == 1) {
                int votes = plugin.getConfig().getInt("voting.pardon", 2);
                return new LadderResolution(
                        Proposal.Type.PARDON, votes, stage, uuid, name,
                        "First council pardon — requires " + votes + " votes."
                );
            }
            if (stage >= 3) {
                int votes = plugin.getConfig().getInt("voting.repardon", 8);
                return new LadderResolution(
                        Proposal.Type.REPARDON, votes, stage, uuid, name,
                        "Re-pardon after a re-ban — requires " + votes + " votes."
                );
            }
            return new LadderResolution(
                    null, 0, stage, uuid, name,
                    "Player is already pardoned by the council (not currently council-banned)."
            );
        });
    }

    public void advanceAfterSuccess(Proposal.Type type, UUID uuid, String name) {
        switch (type) {
            case BAN -> plugin.getDatabaseManager().setBanLadderStage(uuid, name, 1);
            case PARDON -> plugin.getDatabaseManager().setBanLadderStage(uuid, name, 2);
            case REBAN -> plugin.getDatabaseManager().setBanLadderStage(uuid, name, 3);
            case REPARDON -> plugin.getDatabaseManager().setBanLadderStage(uuid, name, 2);
            default -> {}
        }
    }
}
