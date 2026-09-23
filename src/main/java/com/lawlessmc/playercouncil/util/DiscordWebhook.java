package com.lawlessmc.playercouncil.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lawlessmc.playercouncil.PlayerCouncilPlugin;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
        body.addProperty("content", truncate(CoordRedact.apply(content), 1900));
        body.addProperty("username", username);
        postJson(url, body.toString(), null);
    }

    public CompletableFuture<String> createProposalThread(int proposalId, String title, String bodyText) {
        String token = botToken();
        String channelId = plugin.getConfig().getString("discord.proposals-channel-id", "");
        boolean useForum = plugin.getConfig().getBoolean("discord.use-forum", false);
        if (token == null || token.isBlank() || channelId == null || channelId.isBlank()) {
            send("**New Proposal #" + proposalId + "**\n" + bodyText);
            return CompletableFuture.completedFuture(null);
        }
        String threadName = sanitizeThreadName("Proposal #" + proposalId + " — " + title);
        String content = truncate(CoordRedact.apply(bodyText), 1900);
        if (useForum) return createForumPost(token, channelId, threadName, content);
        return createMessageAndThread(token, channelId, threadName, content);
    }

    public void postToThread(String threadId, String content) {
        if (threadId == null || threadId.isBlank()) { send(content); return; }
        String token = botToken();
        if (token == null || token.isBlank()) { send(content); return; }
        JsonObject body = new JsonObject();
        body.addProperty("content", truncate(CoordRedact.apply(content), 1900));
        postJson(API + "/channels/" + threadId + "/messages", body.toString(), token);
    }

    public boolean roleSyncEnabled() {
        return !botToken().isBlank() && !guildId().isBlank() && !councilRoleId().isBlank();
    }

    public String botToken() {
        String inline = plugin.getConfig().getString("discord.bot-token", "");
        if (inline != null && !inline.isBlank()) return inline.trim();
        String file = plugin.getConfig().getString("discord.bot-token-file",
                ".discord-bot-token");
        if (file == null || file.isBlank()) return "";
        try {
            return Files.readString(Path.of(file)).trim();
        } catch (Exception e) {
            return "";
        }
    }

    public String guildId() {
        return snowflake("discord.guild-id");
    }

    public String councilRoleId() {
        return snowflake("discord.council-role-id");
    }

    public String inviteUrl() {
        String url = plugin.getConfig().getString("discord.invite-url", "https://discord.gg/Z23akyxpQZ");
        return url == null || url.isBlank() ? "https://discord.gg/Z23akyxpQZ" : url.trim();
    }

    public String councilChannelId() {
        return snowflake("discord.council-channel-id");
    }

    public String councilChannelUrl() {
        String g = guildId();
        String c = councilChannelId();
        if (g.isBlank() || c.isBlank()) return "";
        return "https://discord.com/channels/" + g + "/" + c;
    }

    public CompletableFuture<Boolean> addCouncilRole(String discordId) {
        return setCouncilRole(discordId, true);
    }

    public CompletableFuture<Boolean> removeCouncilRole(String discordId) {
        return setCouncilRole(discordId, false);
    }

    public CompletableFuture<Boolean> setCouncilRole(String discordId, boolean add) {
        if (!roleSyncEnabled() || discordId == null || discordId.isBlank()) {
            return CompletableFuture.completedFuture(false);
        }
        String url = API + "/guilds/" + guildId() + "/members/" + discordId + "/roles/" + councilRoleId();
        String method = add ? "PUT" : "DELETE";
        return request(method, url, null, botToken()).thenApply(resp -> {
            if (resp == null) return false;
            int code = resp.statusCode();
            if (code == 204 || code == 200) return true;
            if (code == 404) {
                plugin.getLogger().info("Discord member " + discordId + " not in guild (role "
                        + (add ? "add" : "remove") + ")");
                return false;
            }
            logFail(add ? "add council role" : "remove council role", resp);
            return false;
        });
    }

    /**
     * Exact username match in the guild (not nickname / display name).
     */
    public CompletableFuture<GuildUser> findMemberByUsername(String username) {
        if (!roleSyncEnabled() || username == null || username.isBlank()) {
            return CompletableFuture.completedFuture(null);
        }
        String raw = username.startsWith("@") ? username.substring(1) : username;
        final String q = raw.trim();
        if (q.isEmpty()) return CompletableFuture.completedFuture(null);
        String url = API + "/guilds/" + guildId() + "/members/search?query="
                + URLEncoder.encode(q, StandardCharsets.UTF_8) + "&limit=10";
        return request("GET", url, null, botToken()).thenApply(resp -> {
            if (resp == null || resp.statusCode() < 200 || resp.statusCode() >= 300) {
                logFail("member search", resp);
                return null;
            }
            try {
                JsonArray arr = JsonParser.parseString(resp.body()).getAsJsonArray();
                GuildUser exact = null;
                int exactCount = 0;
                for (JsonElement el : arr) {
                    if (!el.isJsonObject()) continue;
                    JsonObject user = el.getAsJsonObject().getAsJsonObject("user");
                    if (user == null) continue;
                    String name = user.has("username") ? user.get("username").getAsString() : "";
                    if (name.equalsIgnoreCase(q)) {
                        exactCount++;
                        exact = new GuildUser(
                                user.get("id").getAsString(),
                                name,
                                user.has("global_name") && !user.get("global_name").isJsonNull()
                                        ? user.get("global_name").getAsString() : name);
                    }
                }
                return exactCount == 1 ? exact : null;
            } catch (Exception e) {
                plugin.getLogger().warning("Discord member search parse failed: " + e.getMessage());
                return null;
            }
        });
    }

    public CompletableFuture<GuildUser> getGuildMember(String discordId) {
        if (!roleSyncEnabled() || discordId == null || discordId.isBlank()) {
            return CompletableFuture.completedFuture(null);
        }
        String url = API + "/guilds/" + guildId() + "/members/" + discordId;
        return request("GET", url, null, botToken()).thenApply(resp -> {
            if (resp == null || resp.statusCode() < 200 || resp.statusCode() >= 300) {
                if (resp != null && resp.statusCode() != 404) logFail("get member", resp);
                return null;
            }
            try {
                JsonObject member = JsonParser.parseString(resp.body()).getAsJsonObject();
                JsonObject user = member.getAsJsonObject("user");
                if (user == null) return null;
                String name = user.has("username") ? user.get("username").getAsString() : "";
                String global = user.has("global_name") && !user.get("global_name").isJsonNull()
                        ? user.get("global_name").getAsString() : name;
                return new GuildUser(user.get("id").getAsString(), name, global);
            } catch (Exception e) {
                return null;
            }
        });
    }

    public CompletableFuture<String> botUsername() {
        String token = botToken();
        if (token.isBlank()) return CompletableFuture.completedFuture(null);
        return request("GET", API + "/users/@me", null, token).thenApply(resp -> {
            if (resp == null || resp.statusCode() < 200 || resp.statusCode() >= 300) return null;
            try {
                JsonObject json = JsonParser.parseString(resp.body()).getAsJsonObject();
                return json.has("username") ? json.get("username").getAsString() : null;
            } catch (Exception e) {
                return null;
            }
        });
    }

    public record GuildUser(String id, String username, String globalName) {}

    private String snowflake(String path) {
        String s = plugin.getConfig().getString(path, "");
        if (s != null && !s.isBlank() && !s.equals("0")) return s.trim();
        long n = plugin.getConfig().getLong(path, 0L);
        return n == 0L ? "" : Long.toString(n);
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

    private void postJson(String url, String json, String botToken) { request("POST", url, json, botToken); }

    private CompletableFuture<HttpResponse<String>> postJsonAsync(String url, String json, String botToken) {
        return request("POST", url, json, botToken);
    }

    private CompletableFuture<HttpResponse<String>> request(String method, String url, String json, String botToken) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder().uri(URI.create(url)).timeout(Duration.ofSeconds(15))
                    .header("User-Agent", "AZPBMD-PlayerCouncil (https://dontplaythisserver.com, 1.2.0)");
            if (json != null) b.header("Content-Type", "application/json");
            if (botToken != null && !botToken.isBlank()) b.header("Authorization", "Bot " + botToken);
            HttpRequest.BodyPublisher body = (json == null)
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(json);
            switch (method) {
                case "GET" -> b.GET();
                case "PUT" -> b.PUT(body);
                case "DELETE" -> b.method("DELETE", body);
                default -> b.POST(body);
            }
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
