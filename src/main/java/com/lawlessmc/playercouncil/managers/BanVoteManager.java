package com.lawlessmc.playercouncil.managers;

import com.lawlessmc.playercouncil.PlayerCouncilPlugin;
import com.lawlessmc.playercouncil.models.Proposal;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

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

    public CompletableFuture<LadderResolution> resolveLadder(String targetName, boolean wantBan) {
        OfflinePlayer off = resolveOffline(targetName);
        UUID uuid = off.getUniqueId();
        String name = off.getName() != null ? off.getName() : targetName;

        return plugin.getDatabaseManager().getBanLadderStageAsync(uuid).thenApply(stage -> {
            if (wantBan) {
                boolean council = isSittingCouncilMember(uuid);
                if (stage <= 0) {
                    if (council) {
                        int votes = plugin.getConfig().getInt("voting.reban", 4);
                        return new LadderResolution(
                                Proposal.Type.REBAN, votes, stage, uuid, name,
                                "Target is a sitting council member — ban requires " + votes + " votes."
                        );
                    }
                    int votes = plugin.getConfig().getInt("voting.ban", 1);
                    return new LadderResolution(
                            Proposal.Type.BAN, votes, stage, uuid, name,
                            "First council ban — requires " + votes + " vote(s)."
                    );
                }
                int votes = plugin.getConfig().getInt("voting.reban", 4);
                return new LadderResolution(
                        Proposal.Type.REBAN, votes, stage, uuid, name,
                        "Player was previously through the council process — re-ban requires " + votes + " votes."
                );
            }

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
