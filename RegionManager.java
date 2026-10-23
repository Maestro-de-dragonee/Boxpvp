package com.boxpvp;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.entity.ThrownExpBottle;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/** Regions with flags (pvp, build, fall-damage, mob-spawn, item-drop, xp-rain) + the selection wand + XP bottle rain. */
public class RegionManager {

    public static final List<String> FLAGS =
            Arrays.asList("pvp", "build", "fall-damage", "mob-spawn", "item-drop", "xp-rain", "hunger");
    public static final String WAND_NAME = ChatColor.GOLD + "Region Wand";

    private final BoxPvP plugin;
    private final File file;
    private final Map<String, Region> regions = new LinkedHashMap<String, Region>();
    private final Map<UUID, Location[]> selections = new HashMap<UUID, Location[]>();
    private final Random rnd = new Random();
    private BukkitTask rainTask;
    private final Map<Block, DecayEntry> decaying = new HashMap<Block, DecayEntry>();
    private int decayCounter = 0;

    /** A player-placed block waiting to vanish, plus whatever it replaced (air, grass, water...). */
    private static class DecayEntry {
        final int id;
        final BlockState replaced;

        DecayEntry(int id, BlockState replaced) {
            this.id = id;
            this.replaced = replaced;
        }
    }

    public RegionManager(BoxPvP plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "regions.yml");
    }

    // ------------------------------------------------------------ persistence

    public void load() {
        regions.clear();
        YamlConfiguration c = YamlConfiguration.loadConfiguration(file);
        for (String key : c.getKeys(false)) {
            ConfigurationSection s = c.getConfigurationSection(key);
            if (s == null) {
                continue;
            }
            Region r = new Region(key);
            r.world = s.getString("world");
            r.minX = s.getInt("minX");
            r.minY = s.getInt("minY");
            r.minZ = s.getInt("minZ");
            r.maxX = s.getInt("maxX");
            r.maxY = s.getInt("maxY");
            r.maxZ = s.getInt("maxZ");
            r.priority = s.getInt("priority");
            r.decaySeconds = s.getInt("decay", 0);
            ConfigurationSection f = s.getConfigurationSection("flags");
            if (f != null) {
                for (String fk : f.getKeys(false)) {
                    r.flags.put(fk, f.getBoolean(fk));
                }
            }
            regions.put(key.toLowerCase(), r);
        }
    }

    public void save() {
        YamlConfiguration c = new YamlConfiguration();
        for (Region r : regions.values()) {
            String p = r.name + ".";
            c.set(p + "world", r.world);
            c.set(p + "minX", r.minX);
            c.set(p + "minY", r.minY);
            c.set(p + "minZ", r.minZ);
            c.set(p + "maxX", r.maxX);
            c.set(p + "maxY", r.maxY);
            c.set(p + "maxZ", r.maxZ);
            c.set(p + "priority", r.priority);
            c.set(p + "decay", r.decaySeconds);
            for (Map.Entry<String, Boolean> e : r.flags.entrySet()) {
                c.set(p + "flags." + e.getKey(), e.getValue());
            }
        }
        try {
            c.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save regions.yml: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------ CRUD

    public Region get(String name) {
        return regions.get(name.toLowerCase());
    }

    public Collection<Region> all() {
        return regions.values();
    }

    public List<String> names() {
        List<String> out = new ArrayList<String>();
        for (Region r : regions.values()) {
            out.add(r.name);
        }
        return out;
    }

    public Region create(String name, Location a, Location b) {
        Region r = new Region(name);
        r.world = a.getWorld().getName();
        r.minX = Math.min(a.getBlockX(), b.getBlockX());
        r.minY = Math.min(a.getBlockY(), b.getBlockY());
        r.minZ = Math.min(a.getBlockZ(), b.getBlockZ());
        r.maxX = Math.max(a.getBlockX(), b.getBlockX());
        r.maxY = Math.max(a.getBlockY(), b.getBlockY());
        r.maxZ = Math.max(a.getBlockZ(), b.getBlockZ());
        regions.put(name.toLowerCase(), r);
        save();
        return r;
    }

    public boolean delete(String name) {
        boolean removed = regions.remove(name.toLowerCase()) != null;
        if (removed) {
            save();
        }
        return removed;
    }

    // ------------------------------------------------------------ flags

    /** Returns the flag value from the highest-priority region that sets it here, or null if none does. */
    public Boolean flagAt(Location loc, String flag) {
        Region best = null;
        for (Region r : regions.values()) {
            if (!r.flags.containsKey(flag) || !r.contains(loc)) {
                continue;
            }
            if (best == null || r.priority > best.priority) {
                best = r;
            }
        }
        return best == null ? null : best.flags.get(flag);
    }

    public boolean allowed(Location loc, String flag) {
        Boolean b = flagAt(loc, flag);
        if (b != null) {
            return b;
        }
        // xp-rain and hunger are OFF unless a region (or the config) turns them on.
        boolean def = !(flag.equals("xp-rain") || flag.equals("hunger"));
        return plugin.getConfig().getBoolean("default-flags." + flag, def);
    }

    // ------------------------------------------------------------ block decay

    /** Seconds after which player-placed blocks vanish at this location (0 = they stay). */
    public int decayAt(Location loc) {
        Region best = null;
        for (Region r : regions.values()) {
            if (r.decaySeconds <= 0 || !r.contains(loc)) {
                continue;
            }
            if (best == null || r.priority > best.priority) {
                best = r;
            }
        }
        return best == null ? 0 : best.decaySeconds;
    }

    /** True if this block was placed by a player and is still waiting to decay. */
    public boolean isDecayTracked(Block b) {
        return decaying.containsKey(b);
    }

    public void trackDecay(final Block b, int seconds, BlockState replaced) {
        final int id = ++decayCounter;
        decaying.put(b, new DecayEntry(id, replaced));
        Bukkit.getScheduler().runTaskLater(plugin, new Runnable() {
            @Override
            public void run() {
                DecayEntry cur = decaying.get(b);
                // Only remove if this exact placement is still the tracked one.
                if (cur != null && cur.id == id) {
                    decaying.remove(b);
                    b.getWorld().spawnParticle(Particle.SMOKE_NORMAL, b.getLocation().add(0.5, 0.5, 0.5), 6, 0.25, 0.25, 0.25, 0.01);
                    restore(b, cur.replaced);
                }
            }
        }, Math.max(1, seconds) * 20L);
    }

    /** Puts back whatever the placed block replaced (normally just air). */
    private void restore(Block b, BlockState replaced) {
        if (replaced != null) {
            replaced.update(true, false);
        } else {
            b.setType(Material.AIR);
        }
    }

    /** The block was broken by a player: stop tracking it, and put back anything it had replaced (e.g. water). */
    public void untrack(Block b) {
        final DecayEntry en = decaying.remove(b);
        if (en != null && en.replaced != null && en.replaced.getType() != Material.AIR) {
            Bukkit.getScheduler().runTask(plugin, new Runnable() {
                @Override
                public void run() {
                    en.replaced.update(true, false);
                }
            });
        }
    }

    /** Removes every block still waiting to decay (called on shutdown). */
    public void clearDecaying() {
        for (Map.Entry<Block, DecayEntry> en : new ArrayList<Map.Entry<Block, DecayEntry>>(decaying.entrySet())) {
            restore(en.getKey(), en.getValue().replaced);
        }
        decaying.clear();
    }

    // ------------------------------------------------------------ wand / selection

    public ItemStack wand() {
        ItemStack it = new ItemStack(Material.WOOD_AXE);
        ItemMeta m = it.getItemMeta();
        m.setDisplayName(WAND_NAME);
        m.setLore(Arrays.asList(ChatColor.GRAY + "Left-click a block: corner 1",
                ChatColor.GRAY + "Right-click a block: corner 2"));
        it.setItemMeta(m);
        return it;
    }

    public boolean isWand(ItemStack it) {
        return it != null && it.getType() == Material.WOOD_AXE && it.hasItemMeta()
                && it.getItemMeta().hasDisplayName() && it.getItemMeta().getDisplayName().equals(WAND_NAME);
    }

    public void setPos(Player p, int index, Location l) {
        Location[] sel = selections.get(p.getUniqueId());
        if (sel == null) {
            sel = new Location[2];
            selections.put(p.getUniqueId(), sel);
        }
        sel[index] = l.getBlock().getLocation();
    }

    public Location[] selection(Player p) {
        return selections.get(p.getUniqueId());
    }

    // ------------------------------------------------------------ XP bottle rain

    public void startRain() {
        stopRain();
        int interval = Math.max(5, plugin.getConfig().getInt("xp-rain.interval-ticks", 20));
        rainTask = Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            @Override
            public void run() {
                rain();
            }
        }, interval, interval);
    }

    public void stopRain() {
        if (rainTask != null) {
            rainTask.cancel();
            rainTask = null;
        }
    }

    private void rain() {
        int n = plugin.getConfig().getInt("xp-rain.bottles-per-pulse", 3);
        for (Region r : regions.values()) {
            Boolean f = r.flags.get("xp-rain");
            if (f == null || !f) {
                continue;
            }
            World w = Bukkit.getWorld(r.world);
            if (w == null) {
                continue;
            }
            boolean anyone = false;
            for (Player p : w.getPlayers()) {
                if (r.contains(p.getLocation())) {
                    anyone = true;
                    break;
                }
            }
            if (!anyone) {
                continue;
            }
            for (int i = 0; i < n; i++) {
                double x = r.minX + rnd.nextDouble() * (r.maxX - r.minX + 1);
                double z = r.minZ + rnd.nextDouble() * (r.maxZ - r.minZ + 1);
                Location l = new Location(w, x, r.maxY + 0.9, z);
                if (!w.isChunkLoaded(l.getBlockX() >> 4, l.getBlockZ() >> 4)) {
                    continue;
                }
                ThrownExpBottle bottle = w.spawn(l, ThrownExpBottle.class);
                bottle.setMetadata("boxpvp_rain", new FixedMetadataValue(plugin, Boolean.TRUE));
            }
        }
    }
}
