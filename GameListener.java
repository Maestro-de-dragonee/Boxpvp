package com.boxpvp;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.ExpBottleEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.projectiles.ProjectileSource;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Combat, stats, killstreaks, respawning and region/mine protection. */
public class GameListener implements Listener {

    private final BoxPvP plugin;
    private final Map<UUID, Long> denyCooldown = new HashMap<UUID, Long>();
    private final Map<String, Long> lastKill = new HashMap<String, Long>();
    private final Map<UUID, Long> pearlCooldown = new HashMap<UUID, Long>();

    public GameListener(BoxPvP plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------ helpers

    /** Creative-mode players ignore every region flag (and don't trigger decay), so admins can always build and repair. */
    private boolean bypass(Player p) {
        return p.getGameMode() == GameMode.CREATIVE;
    }

    private void deny(Player p, String msg) {
        long now = System.currentTimeMillis();
        Long last = denyCooldown.get(p.getUniqueId());
        if (last == null || now - last > 2000L) {
            p.sendMessage(Util.color(msg));
            denyCooldown.put(p.getUniqueId(), now);
        }
    }

    // ------------------------------------------------------------ join / quit

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        final Player p = e.getPlayer();
        plugin.stats().get(p);
        plugin.ranks().applyTab(p);
        if (plugin.getConfig().getBoolean("teleport-to-spawn-on-join", true) && plugin.spawn() != null) {
            p.teleport(plugin.spawn());
        }
        if (!plugin.regions().allowed(p.getLocation(), "hunger")) {
            p.setFoodLevel(20);
            p.setSaturation(20f);
        }
        Bukkit.getScheduler().runTaskLater(plugin, new Runnable() {
            @Override
            public void run() {
                plugin.board().update(p);
            }
        }, 5L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();

        // Combat logging: leaving mid-fight counts as a death for you and a kill for your opponent.
        if (plugin.getConfig().getBoolean("combat-log.enabled", true) && !p.isDead() && plugin.inCombat(p)) {
            UUID opponent = plugin.combatOpponent(p);
            PlayerStats vs = plugin.stats().get(p);
            int lostStreak = vs.streak;
            vs.deaths++;
            vs.streak = 0;
            long penalty = plugin.getConfig().getLong("coins.death-penalty", 0L);
            if (penalty > 0) {
                vs.coins = Math.max(0L, vs.coins - penalty);
            }
            Player killer = opponent == null ? null : Bukkit.getPlayer(opponent);
            if (killer != null && killer.isOnline() && !killer.equals(p)) {
                reward(killer, p, lostStreak);
                plugin.board().update(killer);
            }
            Bukkit.broadcastMessage(Util.color("&c" + p.getName() + " &7logged out during combat and was counted as a death."));
        }

        plugin.board().remove(p);
        plugin.pads().forget(p);
        plugin.clearCombat(p);
        denyCooldown.remove(p.getUniqueId());
        pearlCooldown.remove(p.getUniqueId());
    }

    // ------------------------------------------------------------ damage

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player)) {
            return;
        }
        Player victim = (Player) e.getEntity();
        DamageCause cause = e.getCause();
        if (cause == DamageCause.VOID || cause == DamageCause.SUICIDE) {
            return;
        }

        if (cause == DamageCause.FALL) {
            if (plugin.pads().hasNoFall(victim) || !plugin.regions().allowed(victim.getLocation(), "fall-damage")) {
                e.setCancelled(true);
                return;
            }
        }

        Player attacker = null;
        if (e instanceof EntityDamageByEntityEvent) {
            Entity d = ((EntityDamageByEntityEvent) e).getDamager();
            if (d instanceof Player) {
                attacker = (Player) d;
            } else if (d instanceof Projectile) {
                ProjectileSource src = ((Projectile) d).getShooter();
                if (src instanceof Player) {
                    attacker = (Player) src;
                }
            }
        }

        if (attacker != null && !attacker.equals(victim)) {
            // PvP flag must allow it at BOTH locations.
            if (!bypass(attacker) && (!plugin.regions().allowed(victim.getLocation(), "pvp")
                    || !plugin.regions().allowed(attacker.getLocation(), "pvp"))) {
                e.setCancelled(true);
                return;
            }
            if (plugin.pads().isImmune(victim)) {
                e.setCancelled(true);
                return;
            }
            // Attacking ends your own launch immunity immediately (no camping behind it).
            plugin.pads().endImmunity(attacker);
            plugin.tagCombat(victim, attacker);
            plugin.tagCombat(attacker, victim);
            return;
        }

        // Non-player damage.
        if (plugin.pads().isImmune(victim)) {
            e.setCancelled(true);
            return;
        }
        if ((cause == DamageCause.ENTITY_EXPLOSION || cause == DamageCause.BLOCK_EXPLOSION)
                && !plugin.regions().allowed(victim.getLocation(), "pvp")) {
            e.setCancelled(true);
        }
    }

    // ------------------------------------------------------------ death / respawn

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent e) {
        final Player victim = e.getEntity();
        e.setKeepInventory(true);
        e.getDrops().clear();
        e.setDroppedExp(0);
        if (plugin.getConfig().getBoolean("keep-level", true)) {
            e.setKeepLevel(true);
        }
        if (!plugin.getConfig().getBoolean("death-messages", true)) {
            e.setDeathMessage(null);
        }

        PlayerStats vs = plugin.stats().get(victim);
        int lostStreak = vs.streak;
        vs.deaths++;
        vs.streak = 0;
        long penalty = plugin.getConfig().getLong("coins.death-penalty", 0L);
        if (penalty > 0) {
            vs.coins = Math.max(0L, vs.coins - penalty);
        }

        Player killer = victim.getKiller();
        if (killer != null && !killer.equals(victim)) {
            reward(killer, victim, lostStreak);
            plugin.board().update(killer);
        }
        plugin.board().update(victim);
        plugin.clearCombat(victim);

        // Instant respawn: skip the death screen.
        Bukkit.getScheduler().runTaskLater(plugin, new Runnable() {
            @Override
            public void run() {
                if (victim.isOnline() && victim.isDead()) {
                    victim.spigot().respawn();
                }
            }
        }, 1L);
    }

    private void reward(Player killer, Player victim, int lostStreak) {
        PlayerStats ks = plugin.stats().get(killer);
        ks.kills++;
        ks.streak++;
        if (ks.streak > ks.bestStreak) {
            ks.bestStreak = ks.streak;
        }

        // Anti-farm: repeat kills on the same player within the cooldown earn nothing.
        String pair = killer.getUniqueId() + ":" + victim.getUniqueId();
        long now = System.currentTimeMillis();
        Long last = lastKill.get(pair);
        long cooldown = plugin.getConfig().getLong("coins.anti-farm-seconds", 60L) * 1000L;
        boolean farmed = last != null && now - last < cooldown;
        lastKill.put(pair, now);

        if (farmed) {
            killer.sendMessage(Util.color("&cNo coins - you killed &f" + victim.getName() + " &calready very recently."));
        } else {
            long coins = plugin.getConfig().getLong("coins.per-kill", 10L);
            long bonus = Math.min(plugin.getConfig().getLong("coins.streak-bonus-cap", 40L),
                    (long) (ks.streak - 1) * plugin.getConfig().getLong("coins.streak-bonus-per-kill", 2L));
            long breakBonus = Math.min(plugin.getConfig().getLong("coins.streak-break-cap", 60L),
                    (long) lostStreak * plugin.getConfig().getLong("coins.streak-break-bonus", 3L));
            long total = coins + Math.max(0L, bonus) + Math.max(0L, breakBonus);
            ks.coins += total;
            killer.sendMessage(Util.color("&a+" + total + " coins &7(kill streak: &e" + ks.streak + "&7)"));
        }

        // Streak rewards (always granted, even if the kill itself was farmed - the streak still counts).
        ConfigurationSection rewards = plugin.getConfig().getConfigurationSection("killstreak.rewards");
        if (rewards != null && rewards.isConfigurationSection(String.valueOf(ks.streak))) {
            applyReward(killer, rewards.getConfigurationSection(String.valueOf(ks.streak)), ks.streak);
        }

        int announce = plugin.getConfig().getInt("killstreak.announce-end-at", 5);
        if (announce > 0 && lostStreak >= announce) {
            Bukkit.broadcastMessage(Util.color("&c" + killer.getName() + " &7ended &c" + victim.getName()
                    + "&7's &e" + lostStreak + " &7kill streak!"));
        }
    }

    private void applyReward(Player p, ConfigurationSection sec, int streak) {
        PlayerStats s = plugin.stats().get(p);
        long coins = sec.getLong("coins", 0L);
        if (coins > 0) {
            s.coins += coins;
            p.sendMessage(Util.color("&6Streak reward: &e+" + coins + " coins"));
        }
        for (String item : sec.getStringList("items")) {
            ItemStack st = ItemKey.parseStack(item);
            if (st == null) {
                plugin.getLogger().warning("Bad killstreak reward item: " + item);
                continue;
            }
            Map<Integer, ItemStack> left = p.getInventory().addItem(st);
            for (ItemStack rest : left.values()) {
                p.getWorld().dropItemNaturally(p.getLocation(), rest);
            }
        }
        for (String cmd : sec.getStringList("commands")) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("%player%", p.getName()));
        }
        String msg = sec.getString("message");
        if (msg != null) {
            p.sendMessage(Util.color(msg.replace("%player%", p.getName()).replace("%streak%", String.valueOf(streak))));
        }
        String bc = sec.getString("broadcast");
        if (bc != null) {
            Bukkit.broadcastMessage(Util.color(bc.replace("%player%", p.getName()).replace("%streak%", String.valueOf(streak))));
        }
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        final Player p = e.getPlayer();
        Location s = plugin.spawn();
        if (s != null) {
            e.setRespawnLocation(s);
        }
        plugin.pads().endImmunity(p);
        Bukkit.getScheduler().runTaskLater(plugin, new Runnable() {
            @Override
            public void run() {
                plugin.board().update(p);
            }
        }, 2L);
    }

    // ------------------------------------------------------------ building / mining

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        if (plugin.regions().isWand(p.getInventory().getItemInMainHand())) {
            e.setCancelled(true);
            return;
        }
        if (bypass(p)) {
            return;
        }
        Mine m = plugin.mines().at(e.getBlock());
        if (m != null) {
            plugin.mines().handleBreak(e, m);
            return;
        }
        if (!plugin.regions().allowed(e.getBlock().getLocation(), "build")) {
            e.setCancelled(true);
            deny(p, "&cYou can't break blocks here.");
            return;
        }
        // Inside a decay area only blocks placed by players (the ones that will vanish) can be broken,
        // so the original build can never be damaged.
        if (plugin.regions().decayAt(e.getBlock().getLocation()) > 0 && !plugin.regions().isDecayTracked(e.getBlock())) {
            e.setCancelled(true);
            deny(p, "&cYou can only break blocks that were placed in this area.");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        Player p = e.getPlayer();
        if (bypass(p)) {
            return;
        }
        Block b = e.getBlock();
        if (plugin.mines().at(b) != null || !plugin.regions().allowed(b.getLocation(), "build")) {
            e.setCancelled(true);
            deny(p, "&cYou can't place blocks here.");
        }
    }

    // Blocks placed inside a decay region vanish after the configured time.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlaceDecay(BlockPlaceEvent e) {
        if (bypass(e.getPlayer())) {
            return; // creative builds are permanent
        }
        Block b = e.getBlock();
        int secs = plugin.regions().decayAt(b.getLocation());
        if (secs > 0) {
            plugin.regions().trackDecay(b, secs, e.getBlockReplacedState());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreakDecay(BlockBreakEvent e) {
        plugin.regions().untrack(e.getBlock());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucket(PlayerBucketEmptyEvent e) {
        Player p = e.getPlayer();
        if (bypass(p)) {
            return;
        }
        Block target = e.getBlockClicked().getRelative(e.getBlockFace());
        if (plugin.mines().at(target) != null || !plugin.regions().allowed(target.getLocation(), "build")) {
            e.setCancelled(true);
            deny(p, "&cYou can't place that here.");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) {
        final MineManager mines = plugin.mines();
        final RegionManager regions = plugin.regions();
        java.util.Iterator<Block> it = e.blockList().iterator();
        while (it.hasNext()) {
            Block b = it.next();
            boolean protectedDecayBlock = regions.decayAt(b.getLocation()) > 0 && !regions.isDecayTracked(b);
            if (mines.at(b) != null || !regions.allowed(b.getLocation(), "build") || protectedDecayBlock) {
                it.remove();
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMobSpawn(CreatureSpawnEvent e) {
        if (e.getSpawnReason() == CreatureSpawnEvent.SpawnReason.CUSTOM) {
            return;
        }
        if (!plugin.regions().allowed(e.getLocation(), "mob-spawn")) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        Player p = e.getPlayer();
        if (bypass(p)) {
            return;
        }
        if (!plugin.regions().allowed(p.getLocation(), "item-drop")) {
            e.setCancelled(true);
            deny(p, "&cYou can't drop items here.");
        }
    }

    // ------------------------------------------------------------ exploit fixes

    /** Enderpearl cooldown: refuse to throw while it is running (the pearl is not used up). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPearlUse(PlayerInteractEvent e) {
        Action a = e.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        ItemStack it = e.getItem();
        if (it == null || it.getType() != Material.ENDER_PEARL) {
            return;
        }
        long cd = (long) (plugin.getConfig().getDouble("pearls.cooldown-seconds", 10.0) * 1000.0);
        if (cd <= 0) {
            return;
        }
        Player p = e.getPlayer();
        Long last = pearlCooldown.get(p.getUniqueId());
        long now = System.currentTimeMillis();
        if (last != null && now - last < cd) {
            e.setCancelled(true);
            deny(p, "&cEnder pearl cooldown: &f" + String.format("%.1f", (cd - (now - last)) / 1000.0) + "s");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPearlLaunch(ProjectileLaunchEvent e) {
        if (!(e.getEntity() instanceof EnderPearl)) {
            return;
        }
        ProjectileSource src = e.getEntity().getShooter();
        if (src instanceof Player) {
            pearlCooldown.put(((Player) src).getUniqueId(), System.currentTimeMillis());
        }
    }

    /** No pearling (or chorus-fruiting) from a PvP area into a no-PvP safe zone. The pearl is refunded. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        TeleportCause c = e.getCause();
        if (c != TeleportCause.ENDER_PEARL && c != TeleportCause.CHORUS_FRUIT) {
            return;
        }
        if (!plugin.getConfig().getBoolean("pearls.block-into-safe-zones", true)) {
            return;
        }
        Player p = e.getPlayer();
        if (bypass(p) || e.getTo() == null) {
            return;
        }
        if (!plugin.regions().allowed(e.getTo(), "pvp") && plugin.regions().allowed(e.getFrom(), "pvp")) {
            e.setCancelled(true);
            if (c == TeleportCause.ENDER_PEARL) {
                p.getInventory().addItem(new ItemStack(Material.ENDER_PEARL, 1));
            }
            p.sendMessage(Util.color("&cYou can't teleport into a safe zone from the arena."));
        }
    }

    /** Region flag "hunger": deny = food level can't drop there (eating still works). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHunger(FoodLevelChangeEvent e) {
        if (!(e.getEntity() instanceof Player)) {
            return;
        }
        Player p = (Player) e.getEntity();
        if (e.getFoodLevel() < p.getFoodLevel() && !plugin.regions().allowed(p.getLocation(), "hunger")) {
            e.setCancelled(true);
            // Top the bar back up so nobody is stuck unable to sprint after hunger was turned off.
            p.setFoodLevel(20);
            p.setSaturation(20f);
        }
    }

    // ------------------------------------------------------------ ranks (chat prefix)

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent e) {
        if (!plugin.getConfig().getBoolean("ranks.chat-prefix", true)) {
            return;
        }
        String prefix = plugin.ranks().prefixOf(e.getPlayer().getUniqueId());
        if (prefix == null) {
            return;
        }
        String fmt = Util.color(plugin.getConfig().getString("ranks.chat-format", "%prefix% &f%player%&7: &f%message%"));
        fmt = fmt.replace("%", "%%");
        fmt = fmt.replace("%%prefix%%", prefix.replace("%", "%%"));
        fmt = fmt.replace("%%player%%", "%1$s");
        fmt = fmt.replace("%%message%%", "%2$s");
        if (!fmt.contains("%1$s") || !fmt.contains("%2$s")) {
            fmt = prefix.replace("%", "%%") + " %1$s: %2$s";
        }
        e.setFormat(fmt);
    }

    // ------------------------------------------------------------ wand / xp rain

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Action a = e.getAction();
        if (a != Action.LEFT_CLICK_BLOCK && a != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        if (!plugin.regions().isWand(e.getItem())) {
            return;
        }
        Player p = e.getPlayer();
        if (!p.hasPermission("boxpvp.admin")) {
            return;
        }
        e.setCancelled(true);
        Location l = e.getClickedBlock().getLocation();
        int index = a == Action.LEFT_CLICK_BLOCK ? 0 : 1;
        plugin.regions().setPos(p, index, l);
        plugin.msg(p, "&aCorner " + (index + 1) + " set to &f" + l.getBlockX() + ", " + l.getBlockY() + ", " + l.getBlockZ());
    }

    @EventHandler
    public void onBottle(ExpBottleEvent e) {
        if (e.getEntity().hasMetadata("boxpvp_rain")) {
            e.setExperience(plugin.getConfig().getInt("xp-rain.xp-per-bottle", 8));
        }
    }
}
