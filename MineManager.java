package com.boxpvp;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/** Regenerating, auto-smelting mines. */
public class MineManager {

    private static final Map<Material, Integer> TIER = new HashMap<Material, Integer>();

    static {
        TIER.put(Material.STONE, 0);
        TIER.put(Material.COBBLESTONE, 0);
        TIER.put(Material.COAL_ORE, 0);
        TIER.put(Material.QUARTZ_ORE, 0);
        TIER.put(Material.IRON_ORE, 1);
        TIER.put(Material.LAPIS_ORE, 1);
        TIER.put(Material.GOLD_ORE, 2);
        TIER.put(Material.DIAMOND_ORE, 2);
        TIER.put(Material.EMERALD_ORE, 2);
        TIER.put(Material.REDSTONE_ORE, 2);
        TIER.put(Material.GLOWING_REDSTONE_ORE, 2);
        TIER.put(Material.OBSIDIAN, 3);
    }

    private final BoxPvP plugin;
    private final File file;
    private final Map<String, Mine> mines = new LinkedHashMap<String, Mine>();
    private final Set<Block> pending = new HashSet<Block>();
    private final Map<Material, Integer> defaults = new LinkedHashMap<Material, Integer>();
    private final Map<Material, ItemKey> smelt = new HashMap<Material, ItemKey>();
    private final Random rnd = new Random();

    public MineManager(BoxPvP plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "mines.yml");
    }

    // ------------------------------------------------------------ persistence

    public void load() {
        mines.clear();
        YamlConfiguration c = YamlConfiguration.loadConfiguration(file);
        for (String key : c.getKeys(false)) {
            ConfigurationSection s = c.getConfigurationSection(key);
            if (s == null) {
                continue;
            }
            Mine m = new Mine(key);
            m.world = s.getString("world");
            m.minX = s.getInt("minX");
            m.minY = s.getInt("minY");
            m.minZ = s.getInt("minZ");
            m.maxX = s.getInt("maxX");
            m.maxY = s.getInt("maxY");
            m.maxZ = s.getInt("maxZ");
            m.regenSeconds = s.getInt("regen", 0);
            ConfigurationSection b = s.getConfigurationSection("blocks");
            if (b != null) {
                for (String bk : b.getKeys(false)) {
                    Material mat = Material.matchMaterial(bk);
                    if (mat != null) {
                        m.weights.put(mat, b.getInt(bk));
                    }
                }
            }
            mines.put(key.toLowerCase(), m);
        }
        loadConfigTables();
    }

    private void loadConfigTables() {
        defaults.clear();
        smelt.clear();
        ConfigurationSection d = plugin.getConfig().getConfigurationSection("mines.default-blocks");
        if (d != null) {
            for (String k : d.getKeys(false)) {
                Material m = Material.matchMaterial(k);
                if (m != null && d.getInt(k) > 0) {
                    defaults.put(m, d.getInt(k));
                }
            }
        }
        ConfigurationSection sm = plugin.getConfig().getConfigurationSection("mines.smelt");
        if (sm != null) {
            for (String k : sm.getKeys(false)) {
                Material from = Material.matchMaterial(k);
                ItemKey to = ItemKey.parse(sm.getString(k));
                if (from != null && to != null) {
                    smelt.put(from, to);
                } else {
                    plugin.getLogger().warning("Bad mines.smelt entry: " + k);
                }
            }
        }
    }

    public void save() {
        YamlConfiguration c = new YamlConfiguration();
        for (Mine m : mines.values()) {
            String p = m.name + ".";
            c.set(p + "world", m.world);
            c.set(p + "minX", m.minX);
            c.set(p + "minY", m.minY);
            c.set(p + "minZ", m.minZ);
            c.set(p + "maxX", m.maxX);
            c.set(p + "maxY", m.maxY);
            c.set(p + "maxZ", m.maxZ);
            c.set(p + "regen", m.regenSeconds);
            for (Map.Entry<Material, Integer> e : m.weights.entrySet()) {
                c.set(p + "blocks." + e.getKey().name(), e.getValue());
            }
        }
        try {
            c.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save mines.yml: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------ CRUD

    public Mine get(String name) {
        return mines.get(name.toLowerCase());
    }

    public Collection<Mine> all() {
        return mines.values();
    }

    public List<String> names() {
        List<String> out = new ArrayList<String>();
        for (Mine m : mines.values()) {
            out.add(m.name);
        }
        return out;
    }

    public Mine create(String name, Location a, Location b) {
        Mine m = new Mine(name);
        m.world = a.getWorld().getName();
        m.minX = Math.min(a.getBlockX(), b.getBlockX());
        m.minY = Math.min(a.getBlockY(), b.getBlockY());
        m.minZ = Math.min(a.getBlockZ(), b.getBlockZ());
        m.maxX = Math.max(a.getBlockX(), b.getBlockX());
        m.maxY = Math.max(a.getBlockY(), b.getBlockY());
        m.maxZ = Math.max(a.getBlockZ(), b.getBlockZ());
        mines.put(name.toLowerCase(), m);
        save();
        return m;
    }

    public boolean delete(String name) {
        boolean removed = mines.remove(name.toLowerCase()) != null;
        if (removed) {
            save();
        }
        return removed;
    }

    public Mine at(Block b) {
        for (Mine m : mines.values()) {
            if (m.contains(b)) {
                return m;
            }
        }
        return null;
    }

    public Map<Material, Integer> effectiveWeights(Mine m) {
        return m.weights.isEmpty() ? defaults : m.weights;
    }

    // ------------------------------------------------------------ filling / picking

    public Material pick(Mine m) {
        Map<Material, Integer> w = effectiveWeights(m);
        int total = 0;
        for (int v : w.values()) {
            total += v;
        }
        if (total <= 0) {
            return Material.STONE;
        }
        int r = rnd.nextInt(total);
        for (Map.Entry<Material, Integer> en : w.entrySet()) {
            r -= en.getValue();
            if (r < 0) {
                return en.getKey();
            }
        }
        return Material.STONE;
    }

    /** Fills the whole mine with random blocks. Returns false if it is larger than the safety limit. */
    public boolean fill(Mine m) {
        if (m.volume() > plugin.getConfig().getLong("mines.max-fill-blocks", 200000L)) {
            return false;
        }
        World w = Bukkit.getWorld(m.world);
        if (w == null) {
            return false;
        }
        for (int x = m.minX; x <= m.maxX; x++) {
            for (int y = m.minY; y <= m.maxY; y++) {
                for (int z = m.minZ; z <= m.maxZ; z++) {
                    w.getBlockAt(x, y, z).setType(pick(m));
                }
            }
        }
        pending.clear();
        return true;
    }

    /** Called on shutdown: turns every waiting bedrock placeholder back into a real block. */
    public void restorePending() {
        for (Block b : new ArrayList<Block>(pending)) {
            Mine m = at(b);
            b.setType(m != null ? pick(m) : Material.STONE);
        }
        pending.clear();
    }

    // ------------------------------------------------------------ breaking

    public void handleBreak(BlockBreakEvent e, Mine m) {
        e.setCancelled(true);
        final Player p = e.getPlayer();
        final Block b = e.getBlock();
        Material type = b.getType();
        if (type == Material.BEDROCK || type == Material.AIR) {
            return;
        }
        if (plugin.getConfig().getBoolean("mines.require-pickaxe-tier", true)) {
            Integer need = TIER.get(type);
            if (need != null && toolTier(p.getInventory().getItemInMainHand()) < need) {
                p.sendMessage(Util.color("&cYou need a better pickaxe to mine this."));
                return;
            }
        }

        ItemStack drop = smeltDrop(baseDrop(b));
        b.setType(Material.BEDROCK);
        pending.add(b);

        java.util.Map<Integer, ItemStack> left = p.getInventory().addItem(drop);
        for (ItemStack rest : left.values()) {
            p.getWorld().dropItemNaturally(p.getLocation(), rest);
        }
        if (plugin.getConfig().getBoolean("mines.tool-wear", true)) {
            wear(p);
        }

        final Material next = pick(m);
        int secs = m.regenSeconds > 0 ? m.regenSeconds : plugin.getConfig().getInt("mines.regen-seconds", 8);
        Bukkit.getScheduler().runTaskLater(plugin, new Runnable() {
            @Override
            public void run() {
                if (pending.remove(b)) {
                    b.setType(next);
                }
            }
        }, Math.max(1, secs) * 20L);
    }

    private static int toolTier(ItemStack it) {
        if (it == null) {
            return -1;
        }
        switch (it.getType()) {
            case WOOD_PICKAXE:
            case GOLD_PICKAXE:
                return 0;
            case STONE_PICKAXE:
                return 1;
            case IRON_PICKAXE:
                return 2;
            case DIAMOND_PICKAXE:
                return 3;
            default:
                return -1;
        }
    }

    @SuppressWarnings("deprecation")
    private ItemStack baseDrop(Block b) {
        Material t = b.getType();
        byte data = b.getData();
        switch (t) {
            case COAL_ORE:
                return new ItemStack(Material.COAL, 1);
            case DIAMOND_ORE:
                return new ItemStack(Material.DIAMOND, 1);
            case EMERALD_ORE:
                return new ItemStack(Material.EMERALD, 1);
            case QUARTZ_ORE:
                return new ItemStack(Material.QUARTZ, 1);
            case LAPIS_ORE:
                return new ItemStack(Material.INK_SACK, 4 + rnd.nextInt(5), (short) 4);
            case REDSTONE_ORE:
            case GLOWING_REDSTONE_ORE:
                return new ItemStack(Material.REDSTONE, 4 + rnd.nextInt(2));
            case STONE:
                return data == 0 ? new ItemStack(Material.COBBLESTONE, 1)
                        : new ItemStack(Material.STONE, 1, (short) data);
            case GRASS:
                return new ItemStack(Material.DIRT, 1);
            case LOG:
            case LOG_2:
                return new ItemStack(t, 1, (short) (data & 3));
            default:
                return new ItemStack(t, 1, (short) data);
        }
    }

    @SuppressWarnings("deprecation")
    private ItemStack smeltDrop(ItemStack drop) {
        ItemKey out = smelt.get(drop.getType());
        if (out != null && drop.getDurability() == 0) {
            return out.stack(drop.getAmount());
        }
        return drop;
    }

    @SuppressWarnings("deprecation")
    private void wear(Player p) {
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand == null || hand.getType().getMaxDurability() <= 0) {
            return;
        }
        int unbreaking = hand.getEnchantmentLevel(Enchantment.DURABILITY);
        if (unbreaking > 0 && rnd.nextInt(unbreaking + 1) != 0) {
            return;
        }
        short dmg = (short) (hand.getDurability() + 1);
        if (dmg >= hand.getType().getMaxDurability()) {
            p.getInventory().setItemInMainHand(null);
            p.playSound(p.getLocation(), Sound.ENTITY_ITEM_BREAK, 1f, 1f);
        } else {
            hand.setDurability(dmg);
            p.getInventory().setItemInMainHand(hand);
        }
    }
}
