package com.boxpvp;

import java.util.UUID;

public class PlayerStats {

    public final UUID id;
    public String name;
    public int kills;
    public int deaths;
    public int streak;
    public int bestStreak;
    public long coins;

    public PlayerStats(UUID id, String name) {
        this.id = id;
        this.name = name;
    }
}
