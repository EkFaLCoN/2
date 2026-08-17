package net.cursedvalley.mobs;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Bir bosun ozel can durumu.
 *
 * Neden ozel can: End Crystal'in vanilla cani 5'tir ve tek vurusta patlar; Giant'in
 * can siniri da bu kadar yuksek degerlere uygun degil. Bu yuzden hasar olaylari
 * iptal edilip can burada elle tutuluyor.
 */
public final class BossState {

    /** Vuran oyuncular ve toplam hasarlari (drop icin aday listesi). */
    private final Map<UUID, Double> attackers = new HashMap<>();

    private final String label;
    private final double maxHealth;
    private double health;

    public BossState(String label, double maxHealth) {
        this.label = label;
        this.maxHealth = maxHealth;
        this.health = maxHealth;
    }

    public double health()    { return health; }
    public double maxHealth() { return maxHealth; }
    public boolean dead()     { return health <= 0.0; }

    /** Hasari isler, boss oldu ise true doner. */
    public boolean hit(Player source, double amount) {
        if (amount <= 0) return dead();
        health = Math.max(0.0, health - amount);
        if (source != null) {
            attackers.merge(source.getUniqueId(), amount, Double::sum);
        }
        return dead();
    }

    public void heal(double amount) {
        if (dead()) return;
        health = Math.min(maxHealth, health + amount);
    }

    public Map<UUID, Double> attackers() {
        return attackers;
    }

    /** Varligin ustunde gorunecek can barini uretir. */
    public Component bar() {
        int slots = 20;
        double ratio = maxHealth <= 0 ? 0 : health / maxHealth;
        int filled = (int) Math.round(ratio * slots);

        StringBuilder sb = new StringBuilder();
        sb.append("|".repeat(Math.max(0, filled)));
        String full = sb.toString();
        String empty = "|".repeat(Math.max(0, slots - filled));

        NamedTextColor barColor = ratio > 0.5 ? NamedTextColor.GREEN
                : ratio > 0.25 ? NamedTextColor.GOLD
                : NamedTextColor.RED;

        return Component.text(label + "  ", NamedTextColor.LIGHT_PURPLE)
                .append(Component.text(full, barColor))
                .append(Component.text(empty, NamedTextColor.DARK_GRAY))
                .append(Component.text("  " + format(health) + " / " + format(maxHealth),
                        NamedTextColor.WHITE));
    }

    private static String format(double v) {
        long n = Math.round(v);
        return String.format("%,d", n).replace(',', '.');
    }

    /** Yardimci: bir varlik bu bosun kendisi mi (null guvenli). */
    public static boolean same(Entity a, Entity b) {
        return a != null && b != null && a.getUniqueId().equals(b.getUniqueId());
    }
}
