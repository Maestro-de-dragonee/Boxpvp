package com.boxpvp;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;
import org.spigotmc.event.entity.EntityDismountEvent;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Launch pads: step on a registered gold block and you are seated on an invisible armour stand that glides
 * smoothly to the pad's landing point, then you are placed exactly on it.
 *
 * The seat is moved by the SERVER every tick, so (unlike pushing the player with velocity) the client can't
 * drift, overshoot or fight the movement. While riding (and briefly after landing) you are immune to damage;
 * attacking ends the immunity immediately.
 */
public class LaunchPadManager implements Listener {

    public static final String TAG_SEAT = "boxpvp_launchseat";

    public static class Pad {
        public final String name;
        public final String world;
        public final int x, y, z;
        public Location landing;
        /** Extra height (blocks) at the middle of the flight. 0 = straight line, negative = use config default. */
        public double arc = -1;

        Pad(String name, String world, int x, int y, int z) {
            this.name = name;
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    private final BoxPvP plugin;
    private final File file;
    private final Map<String, Pad> pads = new LinkedHashMap<String, Pad>();
    private final Map<String, Pad> byBlock = new HashMap<String, Pad>();
    private final Map<UUID, Long> immuneUntil = new HashMap<UUID, Long>();
    private final Map<UUID, Long> noFallUntil = new HashMap<UUID, Long>();
    private final Map<UUID, Long> lastLaunch = new HashMap<UUID, Long>();
    private final Map<UUID, Flight> flights = new HashMap<UUID, Flight>();
    private final Set<UUID> activeSeats = new HashSet<UUID>();

    public LaunchPadManager(BoxPvP plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "launchpads.yml");
    }

    // ------------------------------------------------------------ persistence

    private static String key(String world, int x, int y, int z) {
        return world + "," + x + "," + y + "," + z;
    }

    public void load() {
        pads.clear();
        byBlock.clear();
        YamlConfiguration c = YamlConfiguration.loadConfiguration(file);
        for (String name : c.getKeys(false)) {
            ConfigurationSection s = c.getConfigurationSection(name);
            if (s == null) {
                continue;
            }
            Pad pad = new Pad(name, s.getString("world"), s.getInt("x"), s.getInt("y"), s.getInt("z"));
            pad.arc = s.getDouble("arc", -1.0);
            pad.landing = Util.loadLoc(s, "landing");
            register(pad);
        }
    }

    private void register(Pad pad) {
        pads.put(pad.name.toLowerCase(), pad);
        byBlock.put(key(pad.world, pad.x, pad.y, pad.z), pad);
    }

    public void save() {
        YamlConfiguration c = new YamlConfiguration();
        for (Pad pad : pads.values()) {
            String p = pad.name + ".";
            c.set(p + "world", pad.world);
            c.set(p + "x", pad.x);
            c.set(p + "y", pad.y);
            c.set(p + "z", pad.z);
            c.set(p + "arc", pad.arc);
            if (pad.landing != null) {
                Util.saveLoc(c, p + "landing", pad.landing);
            }
        }
        try {
            c.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save launchpads.yml: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------ CRUD

    public Material padMaterial() {
        Material m = Material.matchMaterial(plugin.getConfig().getString("launchpad.block", "GOLD_BLOCK"));
        return m == null ? Material.GOLD_BLOCK : m;
    }

    public Pad get(String name) {
        return pads.get(name.toLowerCase());
    }

    public Pad create(String name, Block b) {
        Pad pad = new Pad(name, b.getWorld().getName(), b.getX(), b.getY(), b.getZ());
        register(pad);
        save();
        return pad;
    }

    public boolean delete(String name) {
        Pad pad = pads.remove(name.toLowerCase());
        if (pad == null) {
            return false;
        }
        byBlock.remove(key(pad.world, pad.x, pad.y, pad.z));
        save();
        return true;
    }

    public boolean existsAt(Block b) {
        return byBlock.containsKey(key(b.getWorld().getName(), b.getX(), b.getY(), b.getZ()));
    }

    public List<String> names() {
        List<String> out = new ArrayList<String>();
        for (Pad p : pads.values()) {
            out.add(p.name);
        }
        return out;
    }

    /** The arc height this pad actually uses (its own setting, or the config default). */
    public double effectiveArc(Pad pad) {
        return pad.arc >= 0 ? pad.arc : Math.max(0.0, plugin.getConfig().getDouble("launchpad.default-arc", 0.0));
    }

    // ------------------------------------------------------------ immunity

    public boolean isImmune(Player p) {
        Long t = immuneUntil.get(p.getUniqueId());
        if (t == null) {
            return false;
        }
        if (t < System.currentTimeMillis()) {
            immuneUntil.remove(p.getUniqueId());
            return false;
        }
        return true;
    }

    public void endImmunity(Player p) {
        immuneUntil.remove(p.getUniqueId());
    }

    public boolean hasNoFall(Player p) {
        if (flights.containsKey(p.getUniqueId())) {
            return true;
        }
        Long t = noFallUntil.get(p.getUniqueId());
        return t != null && t > System.currentTimeMillis();
    }

    /** Called when a player quits: drops their flight (and seat) if they had one. */
    public void forget(Player p) {
        immuneUntil.remove(p.getUniqueId());
        noFallUntil.remove(p.getUniqueId());
        lastLaunch.remove(p.getUniqueId());
        Flight f = flights.remove(p.getUniqueId());
        if (f != null) {
            f.abort();
        }
    }

    /** Called on shutdown: removes every seat that is still in the air. */
    public void shutdown() {
        for (Flight f : new ArrayList<Flight>(flights.values())) {
            f.abort();
        }
        flights.clear();
    }

    /** Removes seats left behind by a crash. Called on startup. */
    public void cleanupLeftovers() {
        for (World w : Bukkit.getWorlds()) {
            for (ArmorStand a : w.getEntitiesByClass(ArmorStand.class)) {
                if (a.getScoreboardTags().contains(TAG_SEAT) && !activeSeats.contains(a.getUniqueId())) {
                    a.remove();
                }
            }
        }
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent e) {
        for (Entity en : e.getChunk().getEntities()) {
            if (en instanceof ArmorStand && en.getScoreboardTags().contains(TAG_SEAT)
                    && !activeSeats.contains(en.getUniqueId())) {
                en.remove();
            }
        }
    }

    /** You can't hop off the seat mid-flight. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onDismount(EntityDismountEvent e) {
        if (!(e.getEntity() instanceof Player)) {
            return;
        }
        Flight f = flights.get(e.getEntity().getUniqueId());
        if (f != null && !f.finishing && f.isSeat(e.getDismounted())) {
            e.setCancelled(true);
        }
    }

    // ------------------------------------------------------------ triggering

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        if (pads.isEmpty()) {
            return;
        }
        Location to = e.getTo();
        // Players standing on a block have an exact whole-number Y; skip everything mid-air.
        if (to == null || to.getY() % 1.0 != 0.0) {
            return;
        }
        Block below = to.clone().subtract(0, 1, 0).getBlock();
        Pad pad = byBlock.get(key(below.getWorld().getName(), below.getX(), below.getY(), below.getZ()));
        if (pad == null) {
            return;
        }
        Player p = e.getPlayer();
        if (flights.containsKey(p.getUniqueId()) || p.isInsideVehicle()) {
            return;
        }
        // A pad only works while its block is still the pad block type (broken/replaced = disabled).
        if (below.getType() != padMaterial()) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = lastLaunch.get(p.getUniqueId());
        if (last != null && now - last < plugin.getConfig().getLong("launchpad.cooldown-ms", 1500L)) {
            return;
        }
        if (pad.landing == null || pad.landing.getWorld() == null || !pad.landing.getWorld().equals(p.getWorld())) {
            if (p.hasPermission("boxpvp.admin")) {
                p.sendMessage(Util.color("&cLaunch pad '" + pad.name + "' has no valid landing point. Use /launchpad setlanding " + pad.name));
            }
            return;
        }
        lastLaunch.put(p.getUniqueId(), now);
        launch(p, pad);
    }

    private void launch(Player p, Pad pad) {
        FileConfiguration cfg = plugin.getConfig();
        Location start = p.getLocation();
        Location land = pad.landing;

        double dx = land.getX() - start.getX();
        double dy = land.getY() - start.getY();
        double dz = land.getZ() - start.getZ();
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);

        double speed = Math.max(0.2, cfg.getDouble("launchpad.flight-speed", 1.0));
        int total = (int) Math.round(dist / speed);
        total = Math.max(cfg.getInt("launchpad.min-ticks", 14), total);
        total = Math.min(cfg.getInt("launchpad.max-ticks", 160), total);

        double seatOffset = cfg.getDouble("launchpad.seat-offset", 0.35);
        Location seatLoc = start.clone();
        seatLoc.setY(start.getY() + seatOffset);

        // The invisible seat: an armour stand with no hitbox that nothing can hurt.
        ArmorStand seat = start.getWorld().spawn(seatLoc, ArmorStand.class);
        seat.setMarker(true);
        seat.setVisible(false);
        seat.setBasePlate(false);
        seat.setArms(false);
        seat.setInvulnerable(true);
        seat.setSilent(true);
        seat.setCollidable(false);
        seat.setCanPickupItems(false);
        seat.addScoreboardTag(TAG_SEAT);
        activeSeats.add(seat.getUniqueId());

        if (!seat.addPassenger(p)) {
            activeSeats.remove(seat.getUniqueId());
            seat.remove();
            return;
        }

        immuneUntil.put(p.getUniqueId(), Long.MAX_VALUE);
        Flight f = new Flight(p, seat, start, land, total, effectiveArc(pad), seatOffset);
        flights.put(p.getUniqueId(), f);
        f.runTaskTimer(plugin, 1L, 1L);

        World w = p.getWorld();
        w.playSound(start, Sound.ENTITY_FIREWORK_LAUNCH, 1f, 1f);
        w.spawnParticle(Particle.CLOUD, start, 25, 0.4, 0.1, 0.4, 0.05);
    }

    private class Flight extends BukkitRunnable {
        private final Player player;
        private final ArmorStand seat;
        private final UUID seatId;
        private final Location start;
        private final Location target;
        private final int total;
        private final double arc;
        private final double seatOffset;
        private int tick = 0;
        private int stalled = 0;
        private Vector lastSeatPos;
        boolean finishing = false;

        Flight(Player player, ArmorStand seat, Location start, Location target, int total, double arc, double seatOffset) {
            this.player = player;
            this.seat = seat;
            this.seatId = seat.getUniqueId();
            this.start = start.clone();
            this.target = target.clone();
            this.total = total;
            this.arc = arc;
            this.seatOffset = seatOffset;
            this.lastSeatPos = seat.getLocation().toVector();
        }

        boolean isSeat(Entity e) {
            return e != null && e.getUniqueId().equals(seatId);
        }

        /** Where the player's feet should be at progress u (0..1). Pure geometry: a line, plus an optional arc. */
        private Vector pointAt(double u) {
            double x = start.getX() + (target.getX() - start.getX()) * u;
            double z = start.getZ() + (target.getZ() - start.getZ()) * u;
            double y = start.getY() + (target.getY() - start.getY()) * u + arc * 4.0 * u * (1.0 - u);
            return new Vector(x, y, z);
        }

        @Override
        public void run() {
            if (finishing) {
                return;
            }
            if (!player.isOnline() || player.isDead() || !seat.isValid() || !seat.getPassengers().contains(player)) {
                finish();
                return;
            }
            tick++;
            if (tick > total) {
                finish();
                return;
            }

            // Smoothstep: ease in and out so the ride starts and ends gently instead of with a jolt.
            double s = (double) tick / (double) total;
            double u = s * s * (3.0 - 2.0 * s);
            Vector want = pointAt(u);

            Location cur = seat.getLocation();
            Vector v = new Vector(want.getX() - cur.getX(),
                    want.getY() + seatOffset - cur.getY(),
                    want.getZ() - cur.getZ());
            double len = v.length();

            // Safety net: if the seat is stuck (blocked, unloaded chunk...) give up and place the player at the end.
            double moved = cur.toVector().distance(lastSeatPos);
            lastSeatPos = cur.toVector();
            if (tick > 6 && len > 0.2 && moved < 0.01) {
                stalled++;
                if (stalled >= 6) {
                    finish();
                    return;
                }
            } else {
                stalled = 0;
            }

            if (len > 3.5) {
                v.multiply(3.5 / len);
            }
            seat.setVelocity(v);
            player.setFallDistance(0f);
        }

        /** Ends the ride normally: drop the seat and put the player exactly on the landing point. */
        void finish() {
            if (finishing) {
                return;
            }
            finishing = true;
            stopTask();
            UUID id = player.getUniqueId();
            flights.remove(id);
            removeSeat();

            long now = System.currentTimeMillis();
            noFallUntil.put(id, now + 3000L);
            // Only keep immunity if the player hasn't already ended it by attacking.
            if (immuneUntil.containsKey(id)) {
                long ms = (long) (plugin.getConfig().getDouble("launchpad.immunity-seconds", 4.0) * 1000.0);
                immuneUntil.put(id, now + ms);
            }
            if (player.isOnline() && !player.isDead()) {
                Location dest = target.clone();
                // Keep the way the player is looking instead of snapping to the admin's camera direction.
                dest.setYaw(player.getLocation().getYaw());
                dest.setPitch(player.getLocation().getPitch());
                player.teleport(dest);
                player.setFallDistance(0f);
                player.getWorld().spawnParticle(Particle.CLOUD, dest, 15, 0.4, 0.1, 0.4, 0.03);
            }
        }

        /** Hard stop with no teleport (quit / shutdown). */
        void abort() {
            if (finishing) {
                return;
            }
            finishing = true;
            stopTask();
            removeSeat();
        }

        private void stopTask() {
            try {
                cancel();
            } catch (IllegalStateException ignored) {
                // not scheduled
            }
        }

        private void removeSeat() {
            activeSeats.remove(seatId);
            if (seat.isValid()) {
                seat.eject();
                seat.remove();
            }
        }
    }
}
