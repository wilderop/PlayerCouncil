package com.lawlessmc.playercouncil.bridge;

import com.lawlessmc.playercouncil.PlayerCouncilPlugin;
import net.kyori.adventure.audience.MessageType;
import net.kyori.adventure.identity.Identity;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.permissions.PermissibleBase;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class BridgedSender implements CommandSender {
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final PlayerCouncilPlugin plugin;
    private final UUID uuid;
    private final String playerName;
    private final PermissibleBase perm = new PermissibleBase(this);
    private final List<String> lines = new ArrayList<>();
    private final boolean op;
    private final boolean council;
    private String originServer;
    private String world;
    private Integer x;
    private Integer y;
    private Integer z;

    public BridgedSender(PlayerCouncilPlugin plugin, UUID uuid, String playerName, boolean op, boolean council) {
        this.plugin = plugin;
        this.uuid = uuid;
        this.playerName = playerName;
        this.op = op;
        this.council = council;
    }

    public void setRemoteLocation(String server, String world, int x, int y, int z) {
        this.originServer = server;
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public String locationLine() {
        return null;
    }

    public UUID uuid() {
        return uuid;
    }

    public String playerName() {
        return playerName;
    }

    public synchronized List<String> lines() {
        return List.copyOf(lines);
    }

    private synchronized void addLine(Component component) {
        lines.add(MM.serialize(component));
    }

    @Override
    public void sendMessage(String message) {
        if (message == null || message.isEmpty()) {
            addLine(Component.empty());
            return;
        }
        if (message.indexOf('§') >= 0) {
            addLine(LegacyComponentSerializer.legacySection().deserialize(message));
            return;
        }
        addLine(Component.text(message));
    }

    @Override
    public void sendMessage(String... messages) {
        if (messages == null) return;
        for (String m : messages) sendMessage(m);
    }

    @Override
    public void sendMessage(UUID sender, String message) {
        sendMessage(message);
    }

    @Override
    public void sendMessage(UUID sender, String... messages) {
        sendMessage(messages);
    }

    @Override
    public void sendMessage(Component message) {
        addLine(message);
    }

    @Override
    public void sendMessage(Identity source, Component message, MessageType type) {
        addLine(message);
    }

    @Override
    public Server getServer() {
        return plugin.getServer();
    }

    @Override
    public String getName() {
        return playerName;
    }

    @Override
    public Spigot spigot() {
        return new Spigot();
    }

    @Override
    public Component name() {
        return Component.text(playerName);
    }

    @Override
    public boolean isPermissionSet(String name) {
        return perm.isPermissionSet(name);
    }

    @Override
    public boolean isPermissionSet(Permission perm) {
        return this.perm.isPermissionSet(perm);
    }

    @Override
    public boolean hasPermission(String name) {
        if (name == null) return false;
        String n = name.toLowerCase();
        if (n.equals("playercouncil.admin")) return op;
        if (n.equals("playercouncil.council")) return council || op;
        if (n.equals("playercouncil.use")) return true;
        if (op) return true;
        return perm.hasPermission(name);
    }

    @Override
    public boolean hasPermission(Permission perm) {
        return hasPermission(perm.getName());
    }

    @Override
    public PermissionAttachment addAttachment(Plugin plugin, String name, boolean value) {
        return perm.addAttachment(plugin, name, value);
    }

    @Override
    public PermissionAttachment addAttachment(Plugin plugin) {
        return perm.addAttachment(plugin);
    }

    @Override
    public PermissionAttachment addAttachment(Plugin plugin, String name, boolean value, int ticks) {
        return perm.addAttachment(plugin, name, value, ticks);
    }

    @Override
    public PermissionAttachment addAttachment(Plugin plugin, int ticks) {
        return perm.addAttachment(plugin, ticks);
    }

    @Override
    public void removeAttachment(PermissionAttachment attachment) {
        perm.removeAttachment(attachment);
    }

    @Override
    public void recalculatePermissions() {
        perm.recalculatePermissions();
    }

    @Override
    public Set<PermissionAttachmentInfo> getEffectivePermissions() {
        return perm.getEffectivePermissions();
    }

    @Override
    public boolean isOp() {
        return op;
    }

    @Override
    public void setOp(boolean value) {
        // remote sender; op is derived from Paper's offline operator list
    }
}
