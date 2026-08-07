package com.lawlessmc.playercouncil.managers;

import com.lawlessmc.playercouncil.PlayerCouncilPlugin;
import com.lawlessmc.playercouncil.models.Proposal;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class ProposalManager {

    private final PlayerCouncilPlugin plugin;

    public ProposalManager(PlayerCouncilPlugin plugin) {
        this.plugin = plugin;
    }

    public void createProposal(Player proposer, Proposal.Type type, String target, String value) {
        long timeoutDays = plugin.getConfig().getLong("voting.proposal-timeout-days", 7);
        long expires = System.currentTimeMillis() + TimeUnit.DAYS.toMillis(timeoutDays);

        plugin.getDatabaseManager().createProposalAsync(type, proposer.getUniqueId(), target, value, expires)
                .thenAccept(id -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (id < 0) {
                        proposer.sendMessage(MiniMessage.miniMessage().deserialize("<red>Failed to create proposal."));
                        return;
                    }
                    String msg = proposer.getName() + " proposed: " + type.name() + " → " + target
                            + (value != null ? " = " + value : "");
                    plugin.getDatabaseManager().log(msg);
                    broadcast("<yellow>" + proposer.getName() + "</yellow> created proposal <gold>#" + id
                            + "</gold>: " + describe(type, target, value));
                    plugin.getDiscordWebhook().send("**New Proposal #" + id + "**\n"
                            + proposer.getName() + ": " + describe(type, target, value));
                    proposer.sendMessage(MiniMessage.miniMessage().deserialize("<green>Proposal #" + id + " created."));
                }));
    }

    public void vote(Player voter, int proposalId, boolean yes) {
        plugin.getDatabaseManager().getProposalAsync(proposalId).thenAccept(p -> {
            if (p == null || !p.isActive()) {
                Bukkit.getScheduler().runTask(plugin, () ->
                        voter.sendMessage(MiniMessage.miniMessage().deserialize(
                                "<red>Could not vote (invalid id or proposal closed).")));
                return;
            }
            if (p.hasVoted(voter.getUniqueId())) {
                Bukkit.getScheduler().runTask(plugin, () ->
                        voter.sendMessage(MiniMessage.miniMessage().deserialize("<red>You already voted.")));
                return;
            }

            plugin.getDatabaseManager().saveVote(proposalId, voter.getUniqueId(), yes);
            p.addVote(voter.getUniqueId(), yes);

            Bukkit.getScheduler().runTask(plugin, () -> {
                String voteStr = yes ? "<green>YES</green>" : "<red>NO</red>";
                broadcast("<yellow>" + voter.getName() + "</yellow> voted " + voteStr
                        + " on proposal <gold>#" + proposalId + "</gold>");
                plugin.getDiscordWebhook().send(voter.getName() + " voted **" + (yes ? "YES" : "NO")
                        + "** on proposal #" + proposalId);
                plugin.getDatabaseManager().log(voter.getName() + " voted " + (yes ? "yes" : "no")
                        + " on #" + proposalId);
                voter.sendMessage(MiniMessage.miniMessage().deserialize("<green>Vote recorded."));
                checkAndExecute(p);
            });
        });
    }

    public void cancel(Player player, int proposalId) {
        plugin.getDatabaseManager().getProposalAsync(proposalId).thenAccept(p -> {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (p == null || !p.isActive()) {
                    player.sendMessage(MiniMessage.miniMessage().deserialize(
                            "<red>Could not cancel (already closed)."));
                    return;
                }
                if (!p.getProposer().equals(player.getUniqueId())) {
                    player.sendMessage(MiniMessage.miniMessage().deserialize(
                            "<red>Only the proposer can cancel."));
                    return;
                }
                plugin.getDatabaseManager().markProposalCancelled(proposalId);
                broadcast("<yellow>" + player.getName() + "</yellow> cancelled proposal <gold>#"
                        + proposalId + "</gold>");
                plugin.getDatabaseManager().log(player.getName() + " cancelled proposal #" + proposalId);
                player.sendMessage(MiniMessage.miniMessage().deserialize("<green>Proposal cancelled."));
            });
        });
    }

    private void checkAndExecute(Proposal p) {
        int required = getRequiredVotes(p);
        if (p.getYesCount() < required) return;

        plugin.getDatabaseManager().markProposalExecuted(p.getId());
        execute(p);

        String tally = "Yes: " + p.getYesCount() + " | No: " + p.getNoCount();
        broadcast("<green>Proposal #" + p.getId() + " PASSED</green> (" + tally + "): " + p.getDescription());
        plugin.getDiscordWebhook().send("**Proposal #" + p.getId() + " PASSED**\n"
                + p.getDescription() + "\n" + tally);
        plugin.getDatabaseManager().log("Proposal #" + p.getId() + " PASSED - " + p.getDescription());
    }

    private int getRequiredVotes(Proposal p) {
        if (p.getType() == Proposal.Type.BAN || p.getType() == Proposal.Type.REBAN
                || p.getType() == Proposal.Type.PARDON || p.getType() == Proposal.Type.REPARDON) {
            if (p.getValue() != null && !p.getValue().isBlank()) {
                try {
                    return Integer.parseInt(p.getValue().trim());
                } catch (NumberFormatException ignored) {}
            }
        }
        return switch (p.getType()) {
            case BAN -> plugin.getConfig().getInt("voting.ban-new", 1);
            case PARDON -> plugin.getConfig().getInt("voting.pardon", 2);
            case REBAN -> plugin.getConfig().getInt("voting.reban", 4);
            case REPARDON -> plugin.getConfig().getInt("voting.repardon", 8);
            case GAMERULE, PLUGIN_ENABLE, PLUGIN_DISABLE ->
                    plugin.getConfig().getInt("voting.gamerule", 8);
        };
    }

    private void execute(Proposal p) {
        switch (p.getType()) {
            case BAN, REBAN -> Bukkit.getScheduler().runTask(plugin, () -> {
                String reason = p.getType() == Proposal.Type.BAN ? "Council ban" : "Council re-ban";
                dispatchBan(p.getTarget(), reason);
                OfflinePlayer off = plugin.getBanVoteManager().resolveOffline(p.getTarget());
                UUID uuid = off.getUniqueId();
                plugin.getCouncilManager().removeMember(uuid);
                plugin.getBanVoteManager().advanceAfterSuccess(p.getType(), uuid,
                        off.getName() != null ? off.getName() : p.getTarget());
            });
            case PARDON, REPARDON -> Bukkit.getScheduler().runTask(plugin, () -> {
                String reason = p.getType() == Proposal.Type.PARDON ? "Council pardon" : "Council re-pardon";
                dispatchUnban(p.getTarget(), reason);
                OfflinePlayer off = plugin.getBanVoteManager().resolveOffline(p.getTarget());
                UUID targetUuid = off.getUniqueId();
                plugin.getBanVoteManager().advanceAfterSuccess(p.getType(), targetUuid,
                        off.getName() != null ? off.getName() : p.getTarget());
                int days = plugin.getConfig().getInt("voting.ban-cooldown-days", 7);
                long until = System.currentTimeMillis() + days * 24L * 60L * 60L * 1000L;
                plugin.getDatabaseManager().setBanProposeCooldown(targetUuid, until);
                plugin.getDatabaseManager().log("Ban-propose cooldown applied to "
                        + (off.getName() != null ? off.getName() : p.getTarget())
                        + " for " + days + " day(s) after pardon.");
                offlineNotifyCooldown(off, days);
            });
            case GAMERULE -> Bukkit.getScheduler().runTask(plugin, () -> applyGameRuleAllWorlds(p.getTarget(), p.getValue()));
            case PLUGIN_ENABLE, PLUGIN_DISABLE -> {
                boolean enable = p.getType() == Proposal.Type.PLUGIN_ENABLE;
                plugin.getDatabaseManager().setPendingPluginAction(p.getTarget(), enable);
                scheduleRestart();
            }
        }
    }

    private void dispatchBan(String playerName, String reason) {
        String provider = plugin.getConfig().getString("ban.provider", "smartban");
        String template = plugin.getConfig().getString("ban.ban-command", "ban {player} {reason}");
        String cmd = template
                .replace("{player}", playerName)
                .replace("{reason}", reason == null || reason.isBlank() ? "Council ban" : reason);

        if ("vanilla".equalsIgnoreCase(provider)) {
            OfflinePlayer off = Bukkit.getOfflinePlayer(playerName);
            if (off.getName() != null) {
                Bukkit.getBanList(org.bukkit.BanList.Type.NAME).addBan(
                        off.getName(), reason, (java.util.Date) null, "PlayerCouncil");
            }
            Player online = Bukkit.getPlayerExact(playerName);
            if (online != null) {
                online.kick(net.kyori.adventure.text.Component.text(reason));
            }
            plugin.getLogger().info("Vanilla ban applied to " + playerName + ": " + reason);
        } else {
            boolean ok = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
            plugin.getLogger().info("Ban command [" + provider + "] dispatched (" + ok + "): " + cmd);
        }
        plugin.getDatabaseManager().log("Banned " + playerName + " via " + provider + ": " + reason);
    }

    private void dispatchUnban(String playerName, String reason) {
        String provider = plugin.getConfig().getString("ban.provider", "smartban");
        String template = plugin.getConfig().getString("ban.unban-command", "unban {player} {reason}");
        String cmd = template
                .replace("{player}", playerName)
                .replace("{reason}", reason == null || reason.isBlank() ? "Council pardon" : reason);

        if ("vanilla".equalsIgnoreCase(provider)) {
            OfflinePlayer off = Bukkit.getOfflinePlayer(playerName);
            if (off.getName() != null) {
                Bukkit.getBanList(org.bukkit.BanList.Type.NAME).pardon(off.getName());
            }
            plugin.getLogger().info("Vanilla unban applied to " + playerName);
        } else {
            boolean ok = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
            plugin.getLogger().info("Unban command [" + provider + "] dispatched (" + ok + "): " + cmd);
        }
        plugin.getDatabaseManager().log("Unbanned " + playerName + " via " + provider + ": " + reason);
    }

    private void offlineNotifyCooldown(OfflinePlayer off, int days) {
        Player online = off.getPlayer();
        if (online != null && online.isOnline()) {
            online.sendMessage(MiniMessage.miniMessage().deserialize(
                    "<gray>[<gold>Council</gold>]</gray> <yellow>Your council ban was overturned. "
                            + "You cannot propose bans for <white>" + days + "</white> day(s)."));
        }
    }

    private void applyGameRuleAllWorlds(String ruleName, String valueStr) {
        String n = ruleName;
        int colon = n.indexOf(':');
        if (colon >= 0) n = n.substring(colon + 1);

        GameRule<?> matched = null;
        for (GameRule<?> rule : GameRule.values()) {
            if (rule.getName().equalsIgnoreCase(n) || rule.getName().equalsIgnoreCase(ruleName)) {
                matched = rule;
                break;
            }
        }

        boolean anyOk = false;
        if (matched != null) {
            final GameRule<?> rule = matched;
            for (var world : Bukkit.getWorlds()) {
                try {
                    Class<?> type = rule.getType();
                    if (type == Boolean.class) {
                        @SuppressWarnings("unchecked")
                        GameRule<Boolean> br = (GameRule<Boolean>) rule;
                        world.setGameRule(br, Boolean.parseBoolean(valueStr));
                        anyOk = true;
                    } else if (type == Integer.class) {
                        @SuppressWarnings("unchecked")
                        GameRule<Integer> ir = (GameRule<Integer>) rule;
                        world.setGameRule(ir, Integer.parseInt(valueStr));
                        anyOk = true;
                    }
                } catch (Exception e) {
                    plugin.getLogger().warning("Failed GameRule API " + ruleName + " on "
                            + world.getName() + ": " + e.getMessage());
                }
            }
        }

        for (var world : Bukkit.getWorlds()) {
            try {
                boolean ok = Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                        "execute in " + world.getKey() + " run gamerule " + n + " " + valueStr);
                if (ok) anyOk = true;
            } catch (Exception e) {
                plugin.getLogger().warning("Failed console gamerule " + n + " on "
                        + world.getName() + ": " + e.getMessage());
            }
        }

        if (anyOk) {
            plugin.getLogger().info("Applied gamerule " + n + "=" + valueStr + " to all worlds.");
        } else {
            plugin.getLogger().warning("Could not apply gamerule " + n + "=" + valueStr);
        }
    }

    private void scheduleRestart() {
        int minutes = plugin.getConfig().getInt("voting.restart-warning-minutes", 10);
        broadcast("<red>Server will restart in " + minutes + " minutes to apply plugin changes.</red>");
        plugin.getDiscordWebhook().send("Server restart scheduled in " + minutes
                + " minutes for plugin toggle.");

        long ticks = minutes * 60L * 20L;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            broadcast("<red>Restarting now...</red>");
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "stop");
        }, ticks);
    }

    public void applyPendingPluginActions() {
        var pending = plugin.getDatabaseManager().getPendingPluginActionsSync();
        if (pending.isEmpty()) return;

        PluginManager pm = Bukkit.getPluginManager();
        for (var entry : pending.entrySet()) {
            String name = entry.getKey();
            boolean enable = entry.getValue();
            Plugin target = pm.getPlugin(name);
            if (target == null) {
                plugin.getLogger().warning("Pending plugin action skipped; not found: " + name);
                continue;
            }
            try {
                if (enable && !target.isEnabled()) {
                    pm.enablePlugin(target);
                    plugin.getLogger().info("Enabled plugin from council vote: " + name);
                    plugin.getDatabaseManager().log("Enabled plugin: " + name);
                } else if (!enable && target.isEnabled()) {
                    pm.disablePlugin(target);
                    plugin.getLogger().info("Disabled plugin from council vote: " + name);
                    plugin.getDatabaseManager().log("Disabled plugin: " + name);
                }
            } catch (Exception e) {
                plugin.getLogger().severe("Failed to toggle plugin " + name + ": " + e.getMessage());
            }
        }
        plugin.getDatabaseManager().clearPendingPluginActions();
    }

    public void getActiveProposals(java.util.function.Consumer<List<Proposal>> callback) {
        plugin.getDatabaseManager().getActiveProposalsAsync()
                .thenAccept(list -> Bukkit.getScheduler().runTask(plugin, () -> callback.accept(list)));
    }

    public void getProposal(int id, java.util.function.Consumer<Proposal> callback) {
        plugin.getDatabaseManager().getProposalAsync(id)
                .thenAccept(p -> Bukkit.getScheduler().runTask(plugin, () -> callback.accept(p)));
    }

    private void broadcast(String mini) {
        Component msg = MiniMessage.miniMessage().deserialize(
                plugin.getConfig().getString("messages.prefix", "") + mini);
        Bukkit.getServer().sendMessage(msg);
    }

    private String describe(Proposal.Type type, String target, String value) {
        return switch (type) {
            case BAN -> "Ban " + target;
            case PARDON -> "Pardon " + target;
            case REBAN -> "Re-ban " + target;
            case REPARDON -> "Re-pardon " + target;
            case GAMERULE -> "Gamerule " + target + " = " + value;
            case PLUGIN_ENABLE -> "Enable plugin " + target;
            case PLUGIN_DISABLE -> "Disable plugin " + target;
        };
    }

    public boolean isValidGameRule(String name) {
        if (name == null || name.isBlank()) return false;
        String n = name;
        int colon = n.indexOf(':');
        if (colon >= 0) n = n.substring(colon + 1);

        for (GameRule<?> rule : GameRule.values()) {
            if (rule.getName().equalsIgnoreCase(n) || rule.getName().equalsIgnoreCase(name)) {
                return true;
            }
        }

        // 1.21.11+ / 26.x snake_case names may not all appear in GameRule.values()
        return n.matches("[a-zA-Z0-9_]+");
    }

    public boolean isWhitelistedPlugin(String name) {
        List<String> list = plugin.getConfig().getStringList("plugins.whitelist");
        return list.stream().anyMatch(s -> s.equalsIgnoreCase(name));
    }
}
