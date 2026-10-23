package com.boxpvp;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class BoxPvP extends JavaPlugin {

    private StatsManager stats;
    private BoardManager board;
    private RegionManager regions;
    private MineManager mines;
    private ShopManager shop;
    private LaunchPadManager pads;
    private DummyManager dummies;
    private RankManager ranks;
    private Location spawn;
    private final Map<UUID, Tag> combat = new HashMap<UUID, Tag>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSpawn();

        stats = new StatsManager(this);
        board = new BoardManager(this);
        regions = new RegionManager(this);
        mines = new MineManager(this);
        shop = new ShopManager(this);
        pads = new LaunchPadManager(this);
        dummies = new DummyManager(this);
        ranks = new RankManager(this);

        stats.load();
        regions.load();
        mines.load();
        shop.load();
        pads.load();
        ranks.load();
        pads.cleanupLeftovers();

        Bukkit.getPluginManager().registerEvents(new GameListener(this), this);
        Bukkit.getPluginManager().registerEvents(shop, this);
        Bukkit.getPluginManager().registerEvents(pads, this);
        Bukkit.getPluginManager().registerEvents(dummies, this);
        dummies.start();

        Commands cmds = new Commands(this);
        String[] names = {"stats", "top", "spawn", "setspawn", "setcoins", "boxpvp", "region", "mine", "npc", "launchpad", "dummy", "rank"};
        for (String name : names) {
            PluginCommand pc = getCommand(name);
            if (pc != null) {
                pc.setExecutor(cmds);
                pc.setTabCompleter(cmds);
            }
        }

        regions.startRain();

        long period = Math.max(1L, getConfig().getLong("autosave-minutes", 5L)) * 60L * 20L;
        Bukkit.getScheduler().runTaskTimer(this, new Runnable() {
            @Override
            public void run() {
                stats.save();
            }
        }, period, period);

        for (Player p : Bukkit.getOnlinePlayers()) {
            stats.get(p);
            board.update(p);
            ranks.applyTab(p);
        }
        getLogger().info("BoxPvP enabled.");
    }

    @Override
    public void onDisable() {
        if (stats != null) {
            stats.save();
        }
        if (pads != null) {
            pads.shutdown();
        }
        if (mines != null) {
            mines.restorePending();
        }
        if (regions != null) {
            regions.stopRain();
            regions.clearDecaying();
        }
    }

    public void reloadAll() {
        reloadConfig();
        regions.load();
        mines.load();
        shop.load();
        pads.load();
        ranks.load();
        regions.startRain();
        for (Player p : Bukkit.getOnlinePlayers()) {
            board.update(p);
            ranks.applyTab(p);
        }
    }

    // ------------------------------------------------------------ accessors

    public StatsManager stats() {
        return stats;
    }

    public BoardManager board() {
        return board;
    }

    public RegionManager regions() {
        return regions;
    }

    public MineManager mines() {
        return mines;
    }

    public ShopManager shop() {
        return shop;
    }

    public LaunchPadManager pads() {
        return pads;
    }

    public DummyManager dummies() {
        return dummies;
    }

    public RankManager ranks() {
        return ranks;
    }

    // ------------------------------------------------------------ spawn

    private File dataFile() {
        return new File(getDataFolder(), "data.yml");
    }

    private void loadSpawn() {
        YamlConfiguration c = YamlConfiguration.loadConfiguration(dataFile());
        spawn = Util.loadLoc(c, "spawn");
    }

    public Location spawn() {
        return spawn == null ? null : spawn.clone();
    }

    public void setSpawn(Location l) {
        spawn = l.clone();
        YamlConfiguration c = new YamlConfiguration();
        Util.saveLoc(c, "spawn", spawn);
        try {
            c.save(dataFile());
        } catch (IOException e) {
            getLogger().warning("Could not save data.yml: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------ combat tag

    private static class Tag {
        final long time;
        final UUID opponent;

        Tag(long time, UUID opponent) {
            this.time = time;
            this.opponent = opponent;
        }
    }

    /** Marks p as in combat, remembering who they were fighting. */
    public void tagCombat(Player p, Player opponent) {
        combat.put(p.getUniqueId(), new Tag(System.currentTimeMillis(), opponent == null ? null : opponent.getUniqueId()));
    }

    public boolean inCombat(Player p) {
        Tag t = combat.get(p.getUniqueId());
        if (t == null) {
            return false;
        }
        return System.currentTimeMillis() - t.time < getConfig().getLong("combat-tag-seconds", 10L) * 1000L;
    }

    /** Who p was last fighting (only while still in combat), or null. */
    public UUID combatOpponent(Player p) {
        Tag t = combat.get(p.getUniqueId());
        return inCombat(p) && t != null ? t.opponent : null;
    }

    public void clearCombat(Player p) {
        combat.remove(p.getUniqueId());
    }

    // ------------------------------------------------------------ messaging

    public void msg(CommandSender to, String text) {
        to.sendMessage(Util.color(getConfig().getString("prefix", "&8[&6BoxPvP&8] &7") + text));
    }
}
