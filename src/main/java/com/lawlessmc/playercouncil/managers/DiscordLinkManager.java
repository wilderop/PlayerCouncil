package com.lawlessmc.playercouncil.managers;

import com.lawlessmc.playercouncil.PlayerCouncilPlugin;
import com.lawlessmc.playercouncil.storage.DatabaseManager.DiscordLink;
import com.lawlessmc.playercouncil.util.DiscordWebhook;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

public class DiscordLinkManager {

    private static final Pattern SNOWFLAKE = Pattern.compile("^[0-9]{17,20}$");

    private final PlayerCouncilPlugin plugin;
    private final MiniMessage mm = MiniMessage.miniMessage();

    public DiscordLinkManager(PlayerCouncilPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean enabled() {
        return plugin.getDiscordWebhook().roleSyncEnabled();
    }

    public void logReady() {
        DiscordWebhook discord = plugin.getDiscordWebhook();
        if (!discord.roleSyncEnabled()) {
            plugin.getLogger().info("Discord council role sync is off (need bot token, guild-id, council-role-id).");
            return;
        }
        discord.botUsername().thenAccept(name -> {
            if (name == null) {
                plugin.getLogger().warning("Discord bot token did not authenticate. Council role sync will fail.");
            } else {
                plugin.getLogger().info("Discord council role sync ready as " + name
                        + " (role " + discord.councilRoleId() + ").");
            }
        });
    }

    public void handleLink(CommandSender sender, UUID uuid, String name, boolean op, String[] args) {
        if (args.length >= 2 && args[1].equalsIgnoreCase("status")) {
            sendStatus(sender, uuid);
            return;
        }
        if (!enabled()) {
            sender.sendMessage(mm.deserialize("<red>Discord council linking is not configured."));
            return;
        }
        plugin.getDatabaseManager().getDiscordLinkByUuidAsync(uuid).thenAccept(existing ->
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (existing != null) {
                        sender.sendMessage(mm.deserialize(
                                "<gray>[<gold>Council</gold>]</gray> Already linked as <white>"
                                        + displayName(existing) + "</white>."));
                        sender.sendMessage(mm.deserialize(
                                "<gray>Use <yellow>/council unlink</yellow> first to switch Discord accounts."));
                        return;
                    }
                    if (args.length < 2) {
                        sendLinkHelp(sender);
                        return;
                    }
                    startLink(sender, uuid, name, op, args[1]);
                }));
    }

    public void handleUnlink(CommandSender sender, UUID uuid) {
        plugin.getDatabaseManager().getDiscordLinkByUuidAsync(uuid).thenAccept(existing -> {
            if (existing == null) {
                plugin.getServer().getScheduler().runTask(plugin, () ->
                        sender.sendMessage(mm.deserialize(
                                "<gray>[<gold>Council</gold>]</gray> You do not have a Discord account linked.")));
                return;
            }
            plugin.getDatabaseManager().deleteDiscordLinkAsync(uuid).thenAccept(ok -> {
                plugin.getDiscordWebhook().removeCouncilRole(existing.discordId());
                plugin.getServer().getScheduler().runTask(plugin, () -> sender.sendMessage(mm.deserialize(
                        "<gray>[<gold>Council</gold>]</gray> <green>Unlinked.</green> "
                                + "<gray>The council Discord role was removed.")));
            });
        });
    }

    public void onCouncilChanged(Set<UUID> added, Set<UUID> removed) {
        if (!enabled()) return;
        plugin.getDatabaseManager().getAllDiscordLinksAsync().thenAccept(links -> {
            for (DiscordLink link : links) {
                if (added.contains(link.uuid())) {
                    plugin.getDiscordWebhook().addCouncilRole(link.discordId());
                } else if (removed.contains(link.uuid()) && !link.keep()) {
                    plugin.getDiscordWebhook().removeCouncilRole(link.discordId());
                }
            }
        });
    }

    public void onRemoved(UUID uuid) {
        if (!enabled()) return;
        plugin.getDatabaseManager().getDiscordLinkByUuidAsync(uuid).thenAccept(link -> {
            if (link != null && !link.keep()) {
                plugin.getDiscordWebhook().removeCouncilRole(link.discordId());
            }
        });
    }

    public void syncAll() {
        if (!enabled()) return;
        plugin.getDatabaseManager().getAllDiscordLinksAsync().thenAccept(links -> {
            for (DiscordLink link : links) {
                boolean sitting = plugin.getCouncilManager().isCouncilMember(link.uuid());
                if (sitting || link.keep()) {
                    plugin.getDiscordWebhook().addCouncilRole(link.discordId());
                } else {
                    plugin.getDiscordWebhook().removeCouncilRole(link.discordId());
                }
            }
            if (!links.isEmpty()) {
                plugin.getLogger().info("Discord council role sync checked " + links.size() + " linked account(s).");
            }
        });
    }

    public void sendUnlinkedHint(CommandSender sender, UUID uuid) {
        if (!enabled()) return;
        plugin.getDatabaseManager().getDiscordLinkByUuidAsync(uuid).thenAccept(link -> {
            if (link != null) return;
            plugin.getServer().getScheduler().runTask(plugin, () -> sender.sendMessage(mm.deserialize(
                    "<gray>Type <click:suggest_command:'/council link '><yellow>/council link</yellow></click> "
                            + "to join the private council Discord.")));
        });
    }

    /** Full link instructions on every login until the sitting member is linked. */
    public void promptUnlinkedOnJoin(Player player) {
        if (!enabled() || player == null || !player.isOnline()) return;
        UUID uuid = player.getUniqueId();
        plugin.getDatabaseManager().getDiscordLinkByUuidAsync(uuid).thenAccept(link -> {
            if (link != null) return;
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) return;
                if (!plugin.getCouncilManager().isCouncilMember(uuid)) return;
                player.sendMessage(mm.deserialize(
                        "<gray>[<gold>Council</gold>]</gray> <yellow>You have not linked Discord yet. "
                                + "#council is members-only."));
                sendLinkHelp(player);
            });
        });
    }

    private void sendLinkHelp(CommandSender sender) {
        String invite = plugin.getDiscordWebhook().inviteUrl();
        sender.sendMessage(mm.deserialize(
                "<gray>[<gold>Council</gold>]</gray> Link Discord to get the private council channel."));
        sender.sendMessage(mm.deserialize(
                "<gray>1. Join </gray><click:open_url:'" + invite + "'><underlined><yellow>"
                        + invite + "</yellow></underlined></click>"));
        sender.sendMessage(mm.deserialize(
                "<gray>2. Then run <yellow>/council link &lt;your Discord username&gt;</yellow>"));
        String ch = plugin.getDiscordWebhook().councilChannelUrl();
        if (!ch.isBlank()) {
            sender.sendMessage(mm.deserialize(
                    "<gray>After linking, </gray><click:open_url:'" + ch
                            + "'><underlined><yellow>#council</yellow></underlined></click>"
                            + "<gray> appears."));
        }
        sender.sendMessage(mm.deserialize(
                "<gray>Use the username in Discord Settings → My Account, not your server nickname."));
        sender.sendMessage(mm.deserialize("<gray>Example: <white>/council link wilder0p</white>"));
    }

    private void sendStatus(CommandSender sender, UUID uuid) {
        plugin.getDatabaseManager().getDiscordLinkByUuidAsync(uuid).thenAccept(link ->
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (link == null) {
                        sender.sendMessage(mm.deserialize(
                                "<gray>[<gold>Council</gold>]</gray> Not linked. Type <yellow>/council link</yellow>."));
                    } else {
                        sender.sendMessage(mm.deserialize(
                                "<gray>[<gold>Council</gold>]</gray> Linked as <white>"
                                        + displayName(link) + "</white>."));
                    }
                }));
    }

    private void startLink(CommandSender sender, UUID uuid, String name, boolean op, String raw) {
        String ident = raw.startsWith("@") ? raw.substring(1).trim() : raw.trim();
        if (ident.isEmpty()) {
            sendLinkHelp(sender);
            return;
        }
        sender.sendMessage(mm.deserialize("<gray>Looking up Discord user <white>" + ident + "</white>..."));
        DiscordWebhook discord = plugin.getDiscordWebhook();
        var found = SNOWFLAKE.matcher(ident).matches()
                ? discord.getGuildMember(ident)
                : discord.findMemberByUsername(ident);
        found.thenAccept(user -> {
            if (user == null) {
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    sender.sendMessage(mm.deserialize(
                            "<red>No Discord member named <white>" + ident
                                    + "</white> in the Discord."));
                    sender.sendMessage(mm.deserialize(
                            "<gray>Join </gray><click:open_url:'" + discord.inviteUrl()
                                    + "'><underlined><yellow>" + discord.inviteUrl()
                                    + "</yellow></underlined></click><gray>, then use your Discord username "
                                    + "(Settings → My Account), not a nickname."));
                });
                return;
            }
            plugin.getDatabaseManager().getDiscordLinkByDiscordIdAsync(user.id()).thenAccept(taken -> {
                if (taken != null && !taken.uuid().equals(uuid)) {
                    plugin.getServer().getScheduler().runTask(plugin, () ->
                            sender.sendMessage(mm.deserialize(
                                    "<red>That Discord account is already linked to another player.")));
                    return;
                }
                plugin.getDatabaseManager()
                        .putDiscordLinkAsync(uuid, user.id(), user.username(), op)
                        .thenAccept(err -> {
                            if (err != null) {
                                plugin.getServer().getScheduler().runTask(plugin, () ->
                                        sender.sendMessage(mm.deserialize("<red>" + err)));
                                return;
                            }
                            boolean sitting = plugin.getCouncilManager().isCouncilMember(uuid);
                            if (!sitting && !op) {
                                tellLinked(sender, name, user, true);
                                return;
                            }
                            discord.addCouncilRole(user.id()).thenAccept(ok ->
                                    tellLinked(sender, name, user, Boolean.TRUE.equals(ok)));
                        });
            });
        });
    }

    private void tellLinked(CommandSender sender, String name, DiscordWebhook.GuildUser user, boolean roleOk) {
        plugin.getLogger().info("Discord linked " + name + " -> " + user.username() + " (" + user.id() + ")");
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            sender.sendMessage(mm.deserialize(
                    "<gray>[<gold>Council</gold>]</gray> <green>Linked to Discord user <white>"
                            + user.username() + "</white>.</green>"));
            if (roleOk) {
                String ch = plugin.getDiscordWebhook().councilChannelUrl();
                if (!ch.isBlank()) {
                    sender.sendMessage(mm.deserialize(
                            "<gray>Open </gray><click:open_url:'" + ch
                                    + "'><underlined><yellow>#council</yellow></underlined></click>"
                                    + "<gray> — it should appear now."));
                } else {
                    sender.sendMessage(mm.deserialize(
                            "<gray>The private #council channel should appear in Discord now."));
                }
            } else {
                sender.sendMessage(mm.deserialize(
                        "<red>The Council role could not be added. In Discord, give AZPBMD Bridge "
                                + "Administrator or Manage Roles, with its role above Council."));
            }
        });
    }

    private static String displayName(DiscordLink link) {
        if (link.discordName() != null && !link.discordName().isBlank()) return link.discordName();
        return link.discordId();
    }
}
