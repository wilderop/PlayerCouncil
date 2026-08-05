package com.lawlessmc.playercouncil;

import com.lawlessmc.playercouncil.commands.*;
import com.lawlessmc.playercouncil.listeners.PlayerListener;
import com.lawlessmc.playercouncil.managers.*;
import com.lawlessmc.playercouncil.storage.DatabaseManager;
import com.lawlessmc.playercouncil.util.DiscordWebhook;
import com.lawlessmc.playercouncil.util.TrackedStats;
import org.bukkit.plugin.java.JavaPlugin;

public class PlayerCouncilPlugin extends JavaPlugin {

    private static PlayerCouncilPlugin instance;

    private DatabaseManager databaseManager;
    private ActivityManager activityManager;
    private CouncilManager councilManager;
    private ProposalManager proposalManager;
    private BanVoteManager banVoteManager;
    private DiscordWebhook discordWebhook;
    private TrackedStats trackedStats;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();

        this.databaseManager = new DatabaseManager(this);
        this.databaseManager.init();

        this.activityManager = new ActivityManager(this);
        this.councilManager = new CouncilManager(this);
        this.proposalManager = new ProposalManager(this);
        this.banVoteManager = new BanVoteManager(this);
        this.discordWebhook = new DiscordWebhook(this);
        this.trackedStats = new TrackedStats(this);

        registerCommands();
        getServer().getPluginManager().registerEvents(new PlayerListener(this), this);

        getServer().getScheduler().runTaskLater(this, () -> {
            proposalManager.applyPendingPluginActions();
            councilManager.loadFromDatabase();
            councilManager.recalculateCouncil();
            getLogger().info("Council seats recalculated on startup.");
        }, 40L);

        getServer().getScheduler().runTaskTimerAsynchronously(this, () ->
                databaseManager.pruneOldSnapshots(30), 20L * 60 * 60, 20L * 60 * 60 * 24);

        long intervalTicks = getConfig().getLong("council.recalc-interval-hours", 24) * 20L * 60L * 60L;
        getServer().getScheduler().runTaskTimer(this, () ->
                councilManager.recalculateCouncil(), intervalTicks, intervalTicks);

        getLogger().info("PlayerCouncil enabled.");
    }

    @Override
    public void onDisable() {
        if (databaseManager != null) {
            databaseManager.close();
        }
        getLogger().info("PlayerCouncil disabled.");
    }

    private void registerCommands() {
        getCommand("council").setExecutor(new CouncilCommand(this));
        getCommand("proposals").setExecutor(new ProposalsCommand(this));
        getCommand("propose").setExecutor(new ProposeCommand(this));
        getCommand("councilvote").setExecutor(new VoteCommand(this));
        getCommand("cancelproposal").setExecutor(new CancelProposalCommand(this));
        getCommand("activity").setExecutor(new ActivityCommand(this));
        getCommand("counciladmin").setExecutor(new CouncilAdminCommand(this));
    }

    public static PlayerCouncilPlugin getInstance() {
        return instance;
    }

    public DatabaseManager getDatabaseManager() {
        return databaseManager;
    }

    public ActivityManager getActivityManager() {
        return activityManager;
    }

    public CouncilManager getCouncilManager() {
        return councilManager;
    }

    public ProposalManager getProposalManager() {
        return proposalManager;
    }

    public BanVoteManager getBanVoteManager() {
        return banVoteManager;
    }

    public DiscordWebhook getDiscordWebhook() {
        return discordWebhook;
    }

    public TrackedStats getTrackedStats() {
        return trackedStats;
    }
}
