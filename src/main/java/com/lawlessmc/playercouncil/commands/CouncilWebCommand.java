package com.lawlessmc.playercouncil.commands;

import com.lawlessmc.playercouncil.PlayerCouncilPlugin;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

import java.security.SecureRandom;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Issues a one-time URL for the council/ops website panel.
 */
public class CouncilWebCommand implements CommandExecutor {

    private static final char[] ALPH = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private static final SecureRandom RND = new SecureRandom();

    private final PlayerCouncilPlugin plugin;
    private final MiniMessage mm = MiniMessage.miniMessage();
    private final Map<UUID, Long> lastIssued = new ConcurrentHashMap<>();

    public CouncilWebCommand(PlayerCouncilPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        UUID actor = com.lawlessmc.playercouncil.bridge.Actors.uuid(sender);
        if (actor == null) {
            sender.sendMessage("Players only. Ops can issue a code from the host with councilweb-issue-code.py.");
            return true;
        }
        String actorName = com.lawlessmc.playercouncil.bridge.Actors.name(sender);
        boolean op = sender.isOp();
        boolean council = plugin.getCouncilManager().isCouncilMember(actor);
        if (!op && !council) {
            sender.sendMessage(mm.deserialize("<red>Unknown command. Type \"/help\" for help."));
            return true;
        }
        var client = plugin.getCouncilWebClient();
        if (client == null || !client.enabled()) {
            sender.sendMessage(mm.deserialize("<red>Council panel login is not configured."));
            return true;
        }
        long now = System.currentTimeMillis();
        Long prev = lastIssued.get(actor);
        int cooldown = client.cooldownSeconds();
        if (prev != null && now - prev < cooldown * 1000L) {
            long wait = (cooldown * 1000L - (now - prev) + 999) / 1000L;
            sender.sendMessage(mm.deserialize("<gray>Wait <yellow>" + wait + "s</yellow> before requesting another link."));
            return true;
        }

        String code = newCode();
        long exp = now / 1000L + client.codeTtlSeconds();
        String role = op ? "ops" : "council";
        String link = client.publicBase() + "/s/" + code;
        lastIssued.put(actor, now);

        client.postCode(code, actor.toString(), actorName, role, exp)
                .thenAccept(ok -> plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (!ok) {
                        lastIssued.remove(actor);
                        sender.sendMessage(mm.deserialize("<red>Could not create a council link. Try again in a moment."));
                        return;
                    }
                    sender.sendMessage(mm.deserialize(
                            "<gray>[<gold>Council</gold>]</gray> <green>One-time link (expires in "
                                    + client.codeTtlSeconds() + "s). Do not share it."));
                    sender.sendMessage(mm.deserialize(
                            "<click:open_url:'" + link + "'><underlined><yellow>" + link + "</yellow></underlined></click>"));
                }));
        return true;
    }

    private static String newCode() {
        char[] buf = new char[10];
        for (int i = 0; i < buf.length; i++) {
            buf[i] = ALPH[RND.nextInt(ALPH.length)];
        }
        return new String(buf);
    }
}
