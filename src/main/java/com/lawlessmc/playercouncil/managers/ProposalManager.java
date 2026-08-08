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
            case SUGGESTION -> plugin.getConfig().getInt("voting.suggestion", 8);
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
            case SUGGESTION -> {
                plugin.getDatabaseManager().log("Council suggestion PASSED: " + p.getTarget());
                plugin.getLogger().info("Council suggestion #" + p.getId() + " passed: " + p.getTarget());
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
        String modern = resolveGameruleName(ruleName);
        String legacy = normalizeRuleKey(ruleName);

        java.util.LinkedHashSet<String> candidates = new java.util.LinkedHashSet<>();
        candidates.add(modern);
        if (!legacy.isEmpty()) candidates.add(legacy);
        if (ruleName != null && !ruleName.isBlank()) {
            String raw = ruleName.trim();
            int colon = raw.indexOf(':');
            if (colon >= 0) raw = raw.substring(colon + 1);
            candidates.add(raw);
        }
        for (var e : GAMERULE_ALIASES.entrySet()) {
            if (e.getValue().equalsIgnoreCase(modern)) {
                candidates.add(e.getKey());
                candidates.add(e.getValue());
            }
        }

        GameRule<?> matched = null;
        for (String cand : candidates) {
            for (GameRule<?> rule : GameRule.values()) {
                if (rule.getName().equalsIgnoreCase(cand)) {
                    matched = rule;
                    break;
                }
            }
            if (matched != null) break;
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
                    plugin.getLogger().warning("Failed GameRule API on " + world.getName()
                            + ": " + e.getMessage());
                }
            }
        }

        for (String cand : candidates) {
            boolean candOk = false;
            for (var world : Bukkit.getWorlds()) {
                try {
                    boolean ok = Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                            "execute in " + world.getKey() + " run gamerule " + cand + " " + valueStr);
                    if (ok) {
                        anyOk = true;
                        candOk = true;
                    }
                } catch (Exception ignored) {
                }
            }
            if (candOk) {
                plugin.getLogger().info("Applied gamerule via console: " + cand + "=" + valueStr);
                break;
            }
        }

        if (anyOk) {
            plugin.getDatabaseManager().log("Applied gamerule " + modern + " = " + valueStr);
            plugin.getLogger().info("Applied gamerule " + modern + "=" + valueStr + " to worlds.");
        } else {
            plugin.getLogger().warning("Could not apply gamerule '" + ruleName
                    + "' (resolved " + modern + ") = " + valueStr);
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
            case SUGGESTION -> "Suggestion: " + target;
        };
    }

    private static final java.util.Map<String, String> GAMERULE_ALIASES = buildGameruleAliases();

    private static java.util.Map<String, String> buildGameruleAliases() {
        java.util.Map<String, String> m = new java.util.HashMap<>();
        String[][] pairs = {
            {"doInsomnia", "spawn_phantoms"},
            {"doDaylightCycle", "advance_time"},
            {"doWeatherCycle", "advance_weather"},
            {"doMobSpawning", "spawn_mobs"},
            {"doMobLoot", "mob_drops"},
            {"doTileDrops", "block_drops"},
            {"doEntityDrops", "entity_drops"},
            {"keepInventory", "keep_inventory"},
            {"mobGriefing", "mob_griefing"},
            {"naturalRegeneration", "natural_health_regeneration"},
            {"doImmediateRespawn", "immediate_respawn"},
            {"doLimitedCrafting", "limited_crafting"},
            {"doPatrolSpawning", "spawn_patrols"},
            {"doTraderSpawning", "spawn_wandering_traders"},
            {"doWardenSpawning", "spawn_wardens"},
            {"doVinesSpread", "spread_vines"},
            {"disableRaids", "raids"},
            {"announceAdvancements", "show_advancement_messages"},
            {"commandBlockOutput", "command_block_output"},
            {"logAdminCommands", "log_admin_commands"},
            {"showDeathMessages", "show_death_messages"},
            {"sendCommandFeedback", "send_command_feedback"},
            {"reducedDebugInfo", "reduced_debug_info"},
            {"spectatorsGenerateChunks", "spectators_generate_chunks"},
            {"spawnRadius", "respawn_radius"},
            {"maxEntityCramming", "max_entity_cramming"},
            {"randomTickSpeed", "random_tick_speed"},
            {"playersSleepingPercentage", "players_sleeping_percentage"},
            {"maxCommandChainLength", "max_command_sequence_length"},
            {"maxCommandForkCount", "max_command_forks"},
            {"commandModificationBlockLimit", "max_block_modifications"},
            {"disableElytraMovementCheck", "elytra_movement_check"},
            {"disablePlayerMovementCheck", "player_movement_check"},
            {"doFireTick", "fire_spread_radius_around_player"},
            {"fallDamage", "fall_damage"},
            {"fireDamage", "fire_damage"},
            {"drowningDamage", "drowning_damage"},
            {"freezeDamage", "freeze_damage"},
            {"pvp", "pvp"},
        };
        for (String[] pair : pairs) {
            m.put(pair[0].toLowerCase(java.util.Locale.ROOT), pair[1]);
            m.put(pair[1].toLowerCase(java.util.Locale.ROOT), pair[1]);
        }
        return java.util.Collections.unmodifiableMap(m);
    }

    private static String normalizeRuleKey(String name) {
        if (name == null) return "";
        String n = name.trim();
        int colon = n.indexOf(':');
        if (colon >= 0) n = n.substring(colon + 1);
        return n.toLowerCase(java.util.Locale.ROOT);
    }

    public String resolveGameruleName(String name) {
        String key = normalizeRuleKey(name);
        if (key.isEmpty()) return name == null ? "" : name.trim();
        return GAMERULE_ALIASES.getOrDefault(key, key);
    }

    public java.util.Set<String> allKnownGameruleNames() {
        java.util.Set<String> names = new java.util.HashSet<>();
        names.addAll(GAMERULE_ALIASES.keySet());
        names.addAll(GAMERULE_ALIASES.values());
        for (GameRule<?> rule : GameRule.values()) {
            names.add(rule.getName().toLowerCase(java.util.Locale.ROOT));
        }
        return names;
    }

    private static int levenshtein(String a, String b) {
        int n = a.length(), m = b.length();
        int[] prev = new int[m + 1];
        int[] cur = new int[m + 1];
        for (int j = 0; j <= m; j++) prev[j] = j;
        for (int i = 1; i <= n; i++) {
            cur[0] = i;
            char ca = a.charAt(i - 1);
            for (int j = 1; j <= m; j++) {
                int cost = (ca == b.charAt(j - 1)) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev; prev = cur; cur = tmp;
        }
        return prev[m];
    }

    public String findClosestGamerule(String input) {
        if (input == null || input.isBlank()) return null;
        String key = normalizeRuleKey(input);
        if (key.isEmpty()) return null;

        if (GAMERULE_ALIASES.containsKey(key)) {
            return resolveGameruleName(key);
        }
        for (GameRule<?> rule : GameRule.values()) {
            if (rule.getName().equalsIgnoreCase(key)) {
                return resolveGameruleName(rule.getName());
            }
        }

        String keyNoUs = key.replace("_", "");

        String best = null;
        int bestDist = Integer.MAX_VALUE;
        for (String candidate : allKnownGameruleNames()) {
            int d = levenshtein(key, candidate);
            int d2 = levenshtein(keyNoUs, candidate.replace("_", ""));
            d = Math.min(d, d2);
            if (d < bestDist) {
                bestDist = d;
                best = candidate;
            } else if (d == bestDist && best != null) {
                String modern = resolveGameruleName(candidate);
                String bestModern = resolveGameruleName(best);
                if (modern.equals(candidate) && !bestModern.equals(best)) {
                    best = candidate;
                }
            }
        }

        if (best == null) return null;
        int maxDist = Math.max(2, key.length() / 4);
        if (bestDist > maxDist) return null;
        return resolveGameruleName(best);
    }

    public String resolveGameruleInput(String input) {
        if (input == null || input.isBlank()) return null;
        String key = normalizeRuleKey(input);
        if (GAMERULE_ALIASES.containsKey(key)) {
            return resolveGameruleName(key);
        }
        for (GameRule<?> rule : GameRule.values()) {
            if (rule.getName().equalsIgnoreCase(key) || rule.getName().equalsIgnoreCase(input.trim())) {
                return resolveGameruleName(rule.getName());
            }
        }
        String fuzzy = findClosestGamerule(input);
        if (fuzzy != null) return fuzzy;
        if (key.matches("[a-z0-9_]+")) return key;
        return null;
    }

    public boolean isValidGameRule(String name) {
        return resolveGameruleInput(name) != null;
    }

    public boolean isWhitelistedPlugin(String name) {
        List<String> list = plugin.getConfig().getStringList("plugins.whitelist");
        return list.stream().anyMatch(s -> s.equalsIgnoreCase(name));
    }
}
