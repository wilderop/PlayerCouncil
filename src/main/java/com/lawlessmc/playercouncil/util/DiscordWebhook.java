package com.lawlessmc.playercouncil.util;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lawlessmc.playercouncil.PlayerCouncilPlugin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

public class DiscordWebhook {

    private static final String API = "https://discord.com/api/v10";

    private final PlayerCouncilPlugin plugin;
    private final HttpClient client;

    public DiscordWebhook(PlayerCouncilPlugin plugin) {
        this.plugin = plugin;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    public void send(String content) {
        String url = plugin.getConfig().getString("discord.webhook-url", "");
        if (url == null || url.isBlank()) return;
        String username = plugin.getConfig().getString("discord.username", "Player Council");
        JsonObject body = new JsonObject();
        body.addProperty("content", truncate(content, 1900));
        body.addProperty("username", username);
        postJson(url, body.toString(), null);
    }

    public CompletableFuture<String> createProposalThread(int proposalId, String title, String bodyText) {
        String token = plugin.getConfig().getString("discord.bot-token", "");
        String channelId = plugin.getConfig().getString("discord.proposals-channel-id", "");
        boolean useForum = plugin.getConfig().getBoolean("discord.use-forum", false);
        if (token == null || token.isBlank() || channelId == null || channelId.isBlank()) {
            send("**New Proposal #" + proposalId + "**\n" + bodyText);
            return CompletableFuture.completedFuture(null);
        }
        String threadName = sanitizeThreadName("Proposal #" + proposalId + " — " + title);
        String content = truncate(bodyText, 1900);
        if (useForum) return createForumPost(token, channelId, threadName, content);
        return createMessageAndThread(token, channelId, threadName, content);
    }

    public void postToThread(String threadId, String content) {
        if (threadId == null || threadId.isBlank()) { send(content); return; }
        String token = plugin.getConfig().getString("discord.bot-token", "");
        if (token == null || token.isBlank()) { send(content); return; }
        JsonObject body = new JsonObject();
        body.addProperty("content", truncate(content, 1900));
        postJson(API + "/channels/" + threadId + "/messages", body.toString(), token);
    }

    private CompletableFuture<String> createForumPost(String token, String channelId, String threadName, String content) {
        JsonObject message = new JsonObject();
        message.addProperty("content", content);
        JsonObject body = new JsonObject();
        body.addProperty("name", threadName);
        body.add("message", message);
        return postJsonAsync(API + "/channels/" + channelId + "/threads", body.toString(), token).thenApply(resp -> {
            if (resp == null || resp.statusCode() < 200 || resp.statusCode() >= 300) { logFail("forum thread", resp); return null; }
            try {
                JsonObject json = JsonParser.parseString(resp.body()).getAsJsonObject();
                return json.has("id") ? json.get("id").getAsString() : null;
            } catch (Exception e) { return null; }
        });
    }

    private CompletableFuture<String> createMessageAndThread(String token, String channelId, String threadName, String content) {
        JsonObject msgBody = new JsonObject();
        msgBody.addProperty("content", content);
        return postJsonAsync(API + "/channels/" + channelId + "/messages", msgBody.toString(), token).thenCompose(msgResp -> {
            if (msgResp == null || msgResp.statusCode() < 200 || msgResp.statusCode() >= 300) {
                logFail("channel message", msgResp);
                return CompletableFuture.completedFuture(null);
            }
            String messageId;
            try { messageId = JsonParser.parseString(msgResp.body()).getAsJsonObject().get("id").getAsString(); }
            catch (Exception e) { return CompletableFuture.completedFuture(null); }
            JsonObject threadBody = new JsonObject();
            threadBody.addProperty("name", threadName);
            threadBody.addProperty("auto_archive_duration", 10080);
            String threadUrl = API + "/channels/" + channelId + "/messages/" + messageId + "/threads";
            return postJsonAsync(threadUrl, threadBody.toString(), token).thenApply(threadResp -> {
                if (threadResp == null || threadResp.statusCode() < 200 || threadResp.statusCode() >= 300) {
                    logFail("start thread", threadResp); return null;
                }
                try {
                    JsonObject json = JsonParser.parseString(threadResp.body()).getAsJsonObject();
                    return json.has("id") ? json.get("id").getAsString() : null;
                } catch (Exception e) { return null; }
            });
        });
    }

    private void postJson(String url, String json, String botToken) { postJsonAsync(url, json, botToken); }

    private CompletableFuture<HttpResponse<String>> postJsonAsync(String url, String json, String botToken) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder().uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json)).timeout(Duration.ofSeconds(15));
            if (botToken != null && !botToken.isBlank()) b.header("Authorization", "Bot " + botToken);
            return client.sendAsync(b.build(), HttpResponse.BodyHandlers.ofString())
                    .exceptionally(ex -> { plugin.getLogger().warning("Discord HTTP failed: " + ex.getMessage()); return null; });
        } catch (Exception e) {
            plugin.getLogger().warning("Discord request build failed: " + e.getMessage());
            return CompletableFuture.completedFuture(null);
        }
    }

    private void logFail(String what, HttpResponse<String> resp) {
        if (resp == null) { plugin.getLogger().warning("Discord " + what + " failed (no response)"); return; }
        plugin.getLogger().warning("Discord " + what + " failed HTTP " + resp.statusCode() + ": " + truncate(resp.body(), 200));
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static String sanitizeThreadName(String name) {
        String n = name.replaceAll("[\\r\\n]", " ").trim();
        if (n.length() > 100) n = n.substring(0, 97) + "...";
        if (n.isEmpty()) n = "Proposal";
        return n;
    }
}
