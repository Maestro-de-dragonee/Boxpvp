package com.boxpvp;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class StatsManager {

    private final BoxPvP plugin;
    private final File file;
    private final Map<UUID, PlayerStats> map = new HashMap<UUID, PlayerStats>();

    public StatsManager(BoxPvP plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "stats.yml");
    }

    public void load() {
        map.clear();
        YamlConfiguration c = YamlConfiguration.loadConfiguration(file);
        for (String key : c.getKeys(false)) {
            ConfigurationSection s = c.getConfigurationSection(key);
            if (s == null) {
                continue;
            }
            try {
                UUID id = UUID.fromString(key);
                PlayerStats ps = new PlayerStats(id, s.getString("name", "unknown"));
                ps.kills = s.getInt("kills");
                ps.deaths = s.getInt("deaths");
                ps.streak = s.getInt("streak");
                ps.bestStreak = s.getInt("best");
                ps.coins = s.getLong("coins");
                map.put(id, ps);
            } catch (IllegalArgumentException ignored) {
                // bad uuid key, skip
            }
        }
    }

    public void save() {
        YamlConfiguration c = new YamlConfiguration();
        for (PlayerStats ps : map.values()) {
            String k = ps.id.toString();
            c.set(k + ".name", ps.name);
            c.set(k + ".kills", ps.kills);
            c.set(k + ".deaths", ps.deaths);
            c.set(k + ".streak", ps.streak);
            c.set(k + ".best", ps.bestStreak);
            c.set(k + ".coins", ps.coins);
        }
        try {
            c.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save stats.yml: " + e.getMessage());
        }
    }

    public PlayerStats get(Player p) {
        PlayerStats ps = map.get(p.getUniqueId());
        if (ps == null) {
            ps = new PlayerStats(p.getUniqueId(), p.getName());
            map.put(p.getUniqueId(), ps);
        }
        ps.name = p.getName();
        return ps;
    }

    public PlayerStats byName(String name) {
        for (PlayerStats ps : map.values()) {
            if (ps.name.equalsIgnoreCase(name)) {
                return ps;
            }
        }
        return null;
    }

    public List<PlayerStats> top(String type, int n) {
        List<PlayerStats> list = new ArrayList<PlayerStats>(map.values());
        Comparator<PlayerStats> cmp;
        if (type.equals("deaths")) {
            cmp = Comparator.comparingInt((PlayerStats a) -> a.deaths);
        } else if (type.equals("streak")) {
            cmp = Comparator.comparingInt((PlayerStats a) -> a.bestStreak);
        } else if (type.equals("coins")) {
            cmp = Comparator.comparingLong((PlayerStats a) -> a.coins);
        } else if (type.equals("kdr")) {
            cmp = Comparator.comparingDouble((PlayerStats a) -> Util.kdr(a.kills, a.deaths));
        } else {
            cmp = Comparator.comparingInt((PlayerStats a) -> a.kills);
        }
        list.sort(cmp.reversed());
        return list.size() > n ? new ArrayList<PlayerStats>(list.subList(0, n)) : list;
    }

    public String value(PlayerStats ps, String type) {
        switch (type) {
            case "deaths":
                return String.valueOf(ps.deaths);
            case "streak":
                return String.valueOf(ps.bestStreak);
            case "coins":
                return Util.fmt(ps.coins);
            case "kdr":
                return Util.kdrStr(ps.kills, ps.deaths);
            default:
                return String.valueOf(ps.kills);
        }
    }
}
