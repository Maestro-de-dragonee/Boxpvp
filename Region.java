package com.boxpvp;

import org.bukkit.Location;

import java.util.LinkedHashMap;
import java.util.Map;

public class Region {

    public final String name;
    public String world;
    public int minX, minY, minZ, maxX, maxY, maxZ;
    public int priority;
    /** If > 0, blocks players place inside this region vanish after this many seconds. */
    public int decaySeconds;
    public final Map<String, Boolean> flags = new LinkedHashMap<String, Boolean>();

    public Region(String name) {
        this.name = name;
    }

    public boolean contains(Location l) {
        if (l == null || l.getWorld() == null || !l.getWorld().getName().equals(world)) {
            return false;
        }
        int x = l.getBlockX();
        int y = l.getBlockY();
        int z = l.getBlockZ();
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }
}
