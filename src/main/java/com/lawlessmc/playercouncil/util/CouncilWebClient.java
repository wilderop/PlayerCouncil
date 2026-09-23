package com.lawlessmc.playercouncil.util;

import com.google.gson.JsonObject;
import com.lawlessmc.playercouncil.PlayerCouncilPlugin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Posts a one-time council-panel login code to the Montreal website.
 */
public class CouncilWebClient {

    private final PlayerCouncilPlugin plugin;
    private final HttpClient client;

    public CouncilWebClient(PlayerCouncilPlugin plugin) {
        this.plugin = plugin;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    public boolean enabled() {
        return plugin.getConfig().getBoolean("council-web.enabled", true)
                && !token().isBlank()
                && !url().isBlank();
    }

    public String publicBase() {
        String base = plugin.getConfig().getString("council-web.public-base",
                "https://dontplaythisserver.com");
        if (base == null || base.isBlank()) return "https://dontplaythisserver.com";
        return base.replaceAll("/+$", "");
    }

    public int codeTtlSeconds() {
        return Math.max(30, plugin.getConfig().getInt("council-web.code-ttl-seconds", 120));
    }

    public int cooldownSeconds() {
        return Math.max(5, plugin.getConfig().getInt("council-web.cooldown-seconds", 30));
    }

    public CompletableFuture<Boolean> postCode(String code, String uuid, String name, String role, long expEpoch) {
        JsonObject body = new JsonObject();
        body.addProperty("code", code);
        body.addProperty("uuid", uuid);
        body.addProperty("name", name);
        body.addProperty("role", role);
        body.addProperty("exp", expEpoch);
        String json = body.toString();
        String url = url();
        String token = token();
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + token)
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .timeout(Duration.ofSeconds(15))
                    .build();
            return client.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                    .thenApply(resp -> {
                        if (resp.statusCode() >= 200 && resp.statusCode() < 300) return true;
                        plugin.getLogger().warning("council-web login-code HTTP "
                                + resp.statusCode() + ": " + truncate(resp.body(), 200));
                        return false;
                    })
                    .exceptionally(ex -> {
                        plugin.getLogger().warning("council-web login-code failed: " + ex.getMessage());
                        return false;
                    });
        } catch (Exception e) {
            plugin.getLogger().warning("council-web request build failed: " + e.getMessage());
            return CompletableFuture.completedFuture(false);
        }
    }

    private String url() {
        String url = plugin.getConfig().getString("council-web.url",
                "https://dontplaythisserver.com/api/councilweb/login-code");
        return url == null ? "" : url.trim();
    }

    private String token() {
        String inline = plugin.getConfig().getString("council-web.token", "");
        if (inline != null && !inline.isBlank()) return inline.trim();
        String file = plugin.getConfig().getString("council-web.token-file",
                ".push-token");
        if (file == null || file.isBlank()) return "";
        try {
            return Files.readString(Path.of(file)).trim();
        } catch (Exception e) {
            plugin.getLogger().warning("council-web could not read token-file " + file + ": " + e.getMessage());
            return "";
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
