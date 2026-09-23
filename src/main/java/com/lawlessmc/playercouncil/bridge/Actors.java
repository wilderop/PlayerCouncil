package com.lawlessmc.playercouncil.bridge;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.UUID;

public final class Actors {
    private Actors() {}

    public static UUID uuid(CommandSender sender) {
        if (sender instanceof Player p) {
            return p.getUniqueId();
        }
        if (sender instanceof BridgedSender b) {
            return b.uuid();
        }
        return null;
    }

    public static String name(CommandSender sender) {
        if (sender instanceof Player p) {
            return p.getName();
        }
        if (sender instanceof BridgedSender b) {
            return b.playerName();
        }
        return sender.getName();
    }
}
