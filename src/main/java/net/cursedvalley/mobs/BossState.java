package net.cursedvalley.mobs;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
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

    /** Turuncu isaretlerin oldugu can yuzdeleri -- her biri bir kez tetiklenir. */
    private static final double[] PHASES = {0.75, 0.50, 0.25};
    private static final TextColor MARK = TextColor.color(0xFF8C1A);
    private int phasesTaken = 0;

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

    /** Cani tamamen doldurur ve gecilmis esikleri sifirlar (yeniden tetiklensinler). */
    public void healFull() {
        health = maxHealth;
        phasesTaken = 0;
    }

    /**
     * Yeni bir turuncu esik gecildiyse yuzdesini doner (75, 50, 25), yoksa 0.
     * Her esik yalnizca bir kez doner.
     */
    public int takePhase() {
        if (maxHealth <= 0) return 0;
        double ratio = health / maxHealth;
        if (phasesTaken >= PHASES.length) return 0;
        if (ratio > PHASES[phasesTaken]) return 0;
        int pct = (int) Math.round(PHASES[phasesTaken] * 100);
        phasesTaken++;
        return pct;
    }

    public Map<UUID, Double> attackers() {
        return attackers;
    }

    /** Varligin ustunde gorunecek can barini uretir. */
    public Component bar() {
        int slots = 20;
        double ratio = maxHealth <= 0 ? 0 : health / maxHealth;
        int filled = (int) Math.round(ratio * slots);

        NamedTextColor barColor = ratio > 0.5 ? NamedTextColor.GREEN
                : ratio > 0.25 ? NamedTextColor.GOLD
                : NamedTextColor.RED;

        // Esik yuvalari: %75 -> 15. yuva, %50 -> 10, %25 -> 5.
        boolean[] mark = new boolean[slots];
        for (double ph : PHASES) {
            int idx = (int) Math.round(ph * slots);
            if (idx >= 0 && idx < slots) mark[idx] = true;
        }

        Component bar = Component.empty();
        for (int i = 0; i < slots; i++) {
            // Isaretler dolu ya da bos olsun her zaman turuncu gorunur.
            bar = bar.append(Component.text("|",
                    mark[i] ? MARK : (i < filled ? barColor : NamedTextColor.DARK_GRAY)));
        }

        return Component.text(label + "  ", NamedTextColor.LIGHT_PURPLE)
                .append(bar)
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
