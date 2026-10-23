package com.boxpvp;

import org.bukkit.Material;
import org.bukkit.block.Block;

import java.util.LinkedHashMap;
import java.util.Map;

public class Mine {

    public final String name;
    public String world;
    public int minX, minY, minZ, maxX, maxY, maxZ;
    /** Seconds until a mined block regenerates; 0 = use the config default. */
    public int regenSeconds;
    /** Block weights for this mine; empty = use mines.default-blocks from config. */
    public final Map<Material, Integer> weights = new LinkedHashMap<Material, Integer>();

    public Mine(String name) {
        this.name = name;
    }

    public boolean contains(Block b) {
        if (!b.getWorld().getName().equals(world)) {
            return false;
        }
        int x = b.getX();
        int y = b.getY();
        int z = b.getZ();
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    public long volume() {
        return (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
    }
}
