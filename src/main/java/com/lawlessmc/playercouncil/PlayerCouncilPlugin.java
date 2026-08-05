package com.lawlessmc.playercouncil;

import com.lawlessmc.playercouncil.commands.*;
import com.lawlessmc.playercouncil.listeners.PlayerListener;
import com.lawlessmc.playercouncil.managers.*;
import com.lawlessmc.playercouncil.storage.DatabaseManager;
import com.lawlessmc.playercouncil.util.DiscordWebhook;
import com.lawlessmc.playercouncil.util.TrackedStats;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.*;
import java.time.temporal.TemporalAdjusters;

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
            getDatabaseManager().getCouncilUuidsAsync().thenAccept(list ->
                    getServer().getScheduler().runTask(this, () -> {
                        councilManager.loadFromDatabase();
                        if (list == null || list.isEmpty()) {
                            getLogger().info("No saved council seats — running initial ranking.");
                            councilManager.recalculateCouncil();
                        } else {
                            getLogger().info("Loaded " + list.size()
                                    + " council seats from database (next change on weekly schedule).");
                        }
                    }));
        }, 40L);

        getServer().getScheduler().runTaskTimerAsynchronously(this, () ->
                databaseManager.pruneOldSnapshots(30), 20L * 60 * 60, 20L * 60 * 60 * 24);

        scheduleWeeklyCouncilRecalc();

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

    private void scheduleWeeklyCouncilRecalc() {
        String dayName = getConfig().getString("council.recalc-day", "SUNDAY");
        int hour = getConfig().getInt("council.recalc-hour", 0);
        DayOfWeek day;
        try {
            day = DayOfWeek.valueOf(dayName.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            getLogger().warning("Invalid council.recalc-day '" + dayName + "', defaulting to SUNDAY");
            day = DayOfWeek.SUNDAY;
        }
        if (hour < 0 || hour > 23) {
            getLogger().warning("Invalid council.recalc-hour " + hour + ", defaulting to 0");
            hour = 0;
        }

        long delayTicks = ticksUntilNext(day, hour);
        long weekTicks = 7L * 24L * 60L * 60L * 20L;

        getServer().getScheduler().runTaskTimer(this, () -> {
            getLogger().info("Weekly council recalculation starting...");
            councilManager.recalculateCouncil();
        }, delayTicks, weekTicks);

        long hours = delayTicks / (20L * 60L * 60L);
        long mins = (delayTicks / (20L * 60L)) % 60L;
        getLogger().info("Next council recalc scheduled in " + hours + "h " + mins + "m ("
                + day + " at " + String.format("%02d:00", hour) + " server time), then weekly.");
    }

    private long ticksUntilNext(DayOfWeek day, int hour) {
        ZoneId zone = ZoneId.systemDefault();
        ZonedDateTime now = ZonedDateTime.now(zone);
        ZonedDateTime next = now.with(TemporalAdjusters.nextOrSame(day))
                .withHour(hour).withMinute(0).withSecond(0).withNano(0);
        if (!next.isAfter(now)) {
            next = next.plusWeeks(1);
        }
        long millis = Duration.between(now, next).toMillis();
        return Math.max(1L, millis / 50L);
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
