package com.boxpvp;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

/** A material plus optional data value (-1 = any data). Parsed from "MATERIAL" or "MATERIAL:data". */
public final class ItemKey {

    public final Material material;
    public final int data;

    public ItemKey(Material material, int data) {
        this.material = material;
        this.data = data;
    }

    public static ItemKey parse(String s) {
        if (s == null) {
            return null;
        }
        String[] p = s.trim().split(":");
        Material m = Material.matchMaterial(p[0].trim());
        if (m == null) {
            return null;
        }
        int d = -1;
        if (p.length > 1) {
            try {
                d = Integer.parseInt(p[1].trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return new ItemKey(m, d);
    }

    /** Parses "MATERIAL[:data][*amount]" into a stack, or null if invalid. */
    public static ItemStack parseStack(String s) {
        if (s == null) {
            return null;
        }
        String[] p = s.split("\\*");
        ItemKey k = parse(p[0]);
        if (k == null) {
            return null;
        }
        int amount = 1;
        if (p.length > 1) {
            Integer a = Util.parseInt(p[1]);
            if (a != null && a > 0) {
                amount = a;
            }
        }
        return k.stack(amount);
    }

    @SuppressWarnings("deprecation")
    public boolean matches(ItemStack st) {
        if (st == null || st.getType() != material) {
            return false;
        }
        return data < 0 || st.getDurability() == data;
    }

    public ItemStack stack(int amount) {
        return new ItemStack(material, amount, (short) Math.max(0, data));
    }

    public String niceName() {
        String n = material.name().toLowerCase().replace('_', ' ');
        return data > 0 ? n + " (" + data + ")" : n;
    }
}
