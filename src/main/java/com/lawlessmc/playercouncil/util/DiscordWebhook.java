package com.lawlessmc.playercouncil.util;

import com.lawlessmc.playercouncil.PlayerCouncilPlugin;
import com.google.gson.JsonObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class DiscordWebhook {

    private final PlayerCouncilPlugin plugin;
    private final HttpClient client;

    public DiscordWebhook(PlayerCouncilPlugin plugin) {
        this.plugin = plugin;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public void send(String content) {
        String url = plugin.getConfig().getString("discord.webhook-url", "");
        if (url == null || url.isBlank()) return;

        String username = plugin.getConfig().getString("discord.username", "Player Council");

        JsonObject body = new JsonObject();
        body.addProperty("content", content);
        body.addProperty("username", username);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .timeout(Duration.ofSeconds(10))
                .build();

        client.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                .exceptionally(ex -> {
                    plugin.getLogger().warning("Discord webhook failed: " + ex.getMessage());
                    return null;
                });
    }
}
