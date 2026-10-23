package com.boxpvp;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.material.MaterialData;
import org.bukkit.projectiles.ProjectileSource;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Training dummy: a scarecrow made of two invisible armour stands - one wearing a hay bale (the body) and one
 * wearing a pumpkin (the head). It can't be hurt, moved, broken or stripped, and it reports your DPS as you hit it.
 *
 * Every damage event on the dummy is cancelled (so no knockback and no damage), but the damage the hit WOULD
 * have done is measured and used for the DPS readout.
 */
public class DummyManager implements Listener {

    public static final String TAG = "boxpvp_dummy";
    private static final String TAG_GROUP = "boxpvp_dummygrp_";

    // A block worn on an armour stand's head is drawn about 0.625 blocks wide, with its centre
    // roughly 1.69 blocks above the stand's feet. These offsets (relative to the floor) stack the
    // hay bale on the ground with the pumpkin on top of it.
    private static final double BODY_OFFSET = -1.375;
    private static final double HEAD_OFFSET = -0.75;
    private static final double BLOCK_CENTER = 1.69;

    private static class Session {
        long start;
        long last;
        double total;
        double best;
        int hits;

        Session(long now) {
            this.start = now;
            this.last = now;
        }
    }

    private final BoxPvP plugin;
    private final Map<UUID, Session> sessions = new HashMap<UUID, Session>();

    public DummyManager(BoxPvP plugin) {
        this.plugin = plugin;
    }

    /** Starts the timer that prints a summary when a player stops hitting the dummy. */
    public void start() {
        Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            @Override
            public void run() {
                long timeout = timeoutMs();
                long now = System.currentTimeMillis();
                Iterator<Map.Entry<UUID, Session>> it = sessions.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry<UUID, Session> en = it.next();
                    if (now - en.getValue().last < timeout) {
                        continue;
                    }
                    Player p = Bukkit.getPlayer(en.getKey());
                    if (p != null && p.isOnline()) {
                        summarize(p, en.getValue());
                    }
                    it.remove();
                }
            }
        }, 10L, 10L);
    }

    private long timeoutMs() {
        return (long) (plugin.getConfig().getDouble("dummy.session-timeout-seconds", 5.0) * 1000.0);
    }

    // ------------------------------------------------------------ spawning

    public boolean isDummy(Entity en) {
        return en instanceof ArmorStand && en.getScoreboardTags().contains(TAG);
    }

    /** Builds a scarecrow standing on the block the player is in, facing the player. */
    public void spawn(Player p) {
        Location base = p.getLocation();
        base.setX(base.getBlockX() + 0.5);
        base.setY(base.getBlockY());
        base.setZ(base.getBlockZ() + 0.5);
        float yaw = p.getLocation().getYaw() + 180f;
        String group = TAG_GROUP + UUID.randomUUID().toString().substring(0, 8);

        makeStand(base.clone().add(0, BODY_OFFSET, 0), yaw, Material.HAY_BLOCK, group, false);
        makeStand(base.clone().add(0, HEAD_OFFSET, 0), yaw, Material.PUMPKIN, group, true);
    }

    private void makeStand(Location loc, float yaw, Material helmet, String group, boolean named) {
        loc.setYaw(yaw);
        loc.setPitch(0f);
        ArmorStand as = loc.getWorld().spawn(loc, ArmorStand.class);
        as.setGravity(false);
        as.setVisible(false);
        as.setArms(false);
        as.setBasePlate(false);
        as.setSmall(false);
        as.setCanPickupItems(false);
        as.setHelmet(new ItemStack(helmet));
        if (named) {
            as.setCustomName(Util.color(plugin.getConfig().getString("dummy.name", "&c&lTraining Dummy")));
            as.setCustomNameVisible(true);
        }
        as.addScoreboardTag(TAG);
        as.addScoreboardTag(group);
    }

    /** Removes the nearest dummy (both of its parts) within 6 blocks. Returns false if there was none. */
    public boolean removeNearest(Player p) {
        Entity best = null;
        double bestDist = Double.MAX_VALUE;
        for (Entity en : p.getNearbyEntities(6, 6, 6)) {
            if (!isDummy(en)) {
                continue;
            }
            double d = en.getLocation().distanceSquared(p.getLocation());
            if (d < bestDist) {
                bestDist = d;
                best = en;
            }
        }
        if (best == null) {
            return false;
        }
        String group = null;
        for (String t : best.getScoreboardTags()) {
            if (t.startsWith(TAG_GROUP)) {
                group = t;
            }
        }
        if (group != null) {
            for (Entity en : best.getNearbyEntities(2, 4, 2)) {
                if (isDummy(en) && en.getScoreboardTags().contains(group)) {
                    en.remove();
                }
            }
        }
        best.remove();
        return true;
    }

    // ------------------------------------------------------------ events

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDamage(EntityDamageEvent e) {
        Entity victim = e.getEntity();
        if (!isDummy(victim)) {
            return;
        }
        // Cancelling = nothing breaks, nothing moves, it never "dies".
        e.setCancelled(true);
        if (!(e instanceof EntityDamageByEntityEvent)) {
            return;
        }
        Entity d = ((EntityDamageByEntityEvent) e).getDamager();
        Player attacker = null;
        if (d instanceof Player) {
            attacker = (Player) d;
        } else if (d instanceof Projectile) {
            ProjectileSource src = ((Projectile) d).getShooter();
            if (src instanceof Player) {
                attacker = (Player) src;
            }
        }
        if (attacker == null) {
            return;
        }

        // Hit feedback: a thump plus crumbs of whatever block was hit.
        ArmorStand stand = (ArmorStand) victim;
        Material worn = stand.getHelmet() == null ? Material.HAY_BLOCK : stand.getHelmet().getType();
        Location fx = stand.getLocation().add(0, BLOCK_CENTER, 0);
        victim.getWorld().playSound(fx, Sound.ENTITY_ARMORSTAND_HIT, 1f, 1f);
        victim.getWorld().spawnParticle(Particle.BLOCK_CRACK, fx, 14, 0.15, 0.2, 0.15, 0.05, new MaterialData(worn));

        record(attacker, e.getFinalDamage());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCombust(EntityCombustEvent e) {
        if (isDummy(e.getEntity())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEntityEvent e) {
        if (isDummy(e.getRightClicked())) {
            e.setCancelled(true);
        }
    }

    /** Stops players taking the pumpkin / hay bale off the stands by right-clicking. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onManipulate(PlayerArmorStandManipulateEvent e) {
        if (isDummy(e.getRightClicked())) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        sessions.remove(e.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------ DPS maths

    private void record(Player p, double dmg) {
        long now = System.currentTimeMillis();
        Session s = sessions.get(p.getUniqueId());
        if (s == null || now - s.last > timeoutMs()) {
            s = new Session(now);
            sessions.put(p.getUniqueId(), s);
        }
        s.last = now;
        s.total += dmg;
        s.hits++;
        if (dmg > s.best) {
            s.best = dmg;
        }

        if (plugin.getConfig().getBoolean("dummy.actionbar", true)) {
            double dps = dps(s);
            String line = "&c" + f(dmg) + " dmg  &8|  &6DPS: &e" + (dps < 0 ? "..." : f(dps))
                    + "  &8|  &fHits: " + s.hits + "  &8|  &fTotal: " + f(s.total);
            actionBar(p, line);
        }
    }

    /**
     * Average DPS over the session. The last hit is credited with one average hit-interval of time, so
     * hitting every 0.625s for 8 damage reads 12.8 DPS straight away. Needs at least two hits.
     */
    private double dps(Session s) {
        if (s.hits < 2) {
            return -1;
        }
        double secs = (s.last - s.start) / 1000.0;
        if (secs <= 0) {
            return -1;
        }
        return s.total * (s.hits - 1) / (secs * s.hits);
    }

    private void summarize(Player p, Session s) {
        if (!plugin.getConfig().getBoolean("dummy.chat-summary", true)) {
            return;
        }
        double dps = dps(s);
        if (dps < 0) {
            return;
        }
        double secs = (s.last - s.start) / 1000.0;
        p.sendMessage(Util.color("&8&m      &r &cDummy results &8&m      "));
        p.sendMessage(Util.color("&7Total damage: &f" + f(s.total) + " &7over &f" + f(secs) + "s"));
        p.sendMessage(Util.color("&7Average DPS: &6" + f(dps) + "  &8|  &7Hits: &f" + s.hits + "  &8|  &7Best hit: &f" + f(s.best)));
    }

    private String f(double v) {
        return String.format("%.1f", v);
    }

    private void actionBar(Player p, String msg) {
        try {
            p.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(Util.color(msg)));
        } catch (Throwable t) {
            p.sendMessage(Util.color(msg));
        }
    }
}
