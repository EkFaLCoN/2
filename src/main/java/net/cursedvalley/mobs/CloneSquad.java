package net.cursedvalley.mobs;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Giant;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Overlord'un mini klonlari.
 *
 * Overlord cani %25'e dustugunde cagrilir. Her biri kucultulmus Overlord
 * rigini kullanir -- yani vanilla Giant gorunumu degil, bossun kendi modeli.
 *
 * Neden can elle tutuluyor: Minecraft'ta max_health nitelik siniri 1024'tur,
 * 5000 can vanilla yoluyla verilemez. Bosslarda oldugu gibi hasar olayi iptal
 * edilip can burada sayiliyor.
 */
public final class CloneSquad {

    public static final String TAG = "cv_clone";

    private final JavaPlugin plugin;
    private final List<Clone> clones = new ArrayList<>();

    public CloneSquad(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    private static final class Clone {
        final Giant entity;
        final OverlordModel model;
        double health;
        final double maxHealth;
        /** Vuranlar ve hasarlari -- drop sahibini secmek icin. */
        final Map<UUID, Double> attackers = new HashMap<>();
        int jumpTimer;
        boolean airborne;
        long lastMelee;

        Clone(Giant entity, OverlordModel model, double health, int jumpTimer) {
            this.entity = entity;
            this.model = model;
            this.health = health;
            this.maxHealth = health;
            this.jumpTimer = jumpTimer;
        }
    }

    // ==================== DOGUS ====================

    /**
     * Klonlari bossun etrafina halka seklinde dogurur.
     *
     * @param count   kac tane
     * @param health  her birinin cani
     * @param scale   rig olcegi (boss 4.0)
     */
    public void spawn(Location center, int count, double health, float scale, boolean customModel) {
        World w = center.getWorld();
        if (w == null) return;

        for (int i = 0; i < count; i++) {
            double a = (2 * Math.PI * i) / count;
            Location at = center.clone().add(Math.cos(a) * 7.0, 0, Math.sin(a) * 7.0);
            at.setY(w.getHighestBlockYAt(at) + 1.0);

            Giant g = w.spawn(at, Giant.class, e -> {
                e.addScoreboardTag(TAG);
                e.setPersistent(false);
                e.setRemoveWhenFarAway(false);
                e.setSilent(false);
                // Vanilla can siniri 1024; gercek can asagida elle tutuluyor.
                var maxAttr = e.getAttribute(Attribute.MAX_HEALTH);
                if (maxAttr != null) maxAttr.setBaseValue(1024.0);
                e.setHealth(1024.0);
                var scaleAttr = e.getAttribute(Attribute.SCALE);
                if (scaleAttr != null) scaleAttr.setBaseValue(scale / 4.0);
                if (customModel) e.setInvisible(true);
            });

            OverlordModel m = null;
            if (customModel) {
                m = new OverlordModel(plugin, scale);
                m.spawn(g);
            }

            // Ziplamalar ayni anda olmasin diye kaydirilir.
            clones.add(new Clone(g, m, health, 60 + i * 4));
            g.customName(nameOf(health, health));
            g.setCustomNameVisible(true);
        }

        w.playSound(center, Sound.ENTITY_WITHER_SPAWN, SoundCategory.HOSTILE, 2.0f, 1.6f);
    }

    private static Component nameOf(double hp, double max) {
        int pct = (int) Math.round(100.0 * hp / max);
        return Component.text("Mini Overlord ", NamedTextColor.LIGHT_PURPLE)
                .append(Component.text(Math.round(hp) + " / " + Math.round(max),
                        pct > 50 ? NamedTextColor.GREEN
                                 : pct > 25 ? NamedTextColor.GOLD : NamedTextColor.RED));
    }

    // ==================== HASAR ====================

    public boolean isClone(Entity e) {
        return e != null && e.getScoreboardTags().contains(TAG);
    }

    /**
     * Klona hasar uygular.
     *
     * @return klon oldu ise vuranlarin listesi, olmedi ise null
     */
    public List<UUID> hit(Entity victim, Player attacker, double damage) {
        for (Clone c : clones) {
            if (!c.entity.getUniqueId().equals(victim.getUniqueId())) continue;

            if (attacker != null && damage > 0) {
                c.health = Math.max(0, c.health - damage);
                c.attackers.merge(attacker.getUniqueId(), damage, Double::sum);
            }
            c.entity.customName(nameOf(c.health, c.maxHealth));

            if (c.health <= 0) {
                List<UUID> hitters = new ArrayList<>(c.attackers.keySet());
                kill(c);
                clones.remove(c);
                return hitters;
            }
            return null;
        }
        return null;
    }

    private void kill(Clone c) {
        Location at = c.entity.getLocation();
        at.getWorld().playSound(at, Sound.ENTITY_IRON_GOLEM_DEATH, SoundCategory.HOSTILE, 1.4f, 0.7f);
        at.getWorld().spawnParticle(Particle.EXPLOSION, at.clone().add(0, 1.5, 0), 6, 0.8, 1.0, 0.8, 0);
        if (c.model != null) c.model.remove();
        c.entity.remove();
    }

    // ==================== HER TICK ====================

    public void tick() {
        for (Iterator<Clone> it = clones.iterator(); it.hasNext(); ) {
            Clone c = it.next();

            if (c.entity.isDead() || !c.entity.isValid()) {
                if (c.model != null) c.model.remove();
                it.remove();
                continue;
            }

            if (c.model != null) c.model.tick(c.entity);

            // Vanilla can barini hep dolu tut; gercek can bizde.
            if (c.entity.getHealth() < 1024.0) c.entity.setHealth(1024.0);

            // Giant'in vanilla AI'si yok -- hedefi elle takip eder.
            Player near = nearest(c.entity.getLocation());
            if (near != null) {
                c.entity.setTarget(near);
                if (near.getLocation().distance(c.entity.getLocation()) > 2.5) {
                    c.entity.getPathfinder().moveTo(near, 1.1);
                }

                // Yakin dovus
                long now = c.entity.getWorld().getFullTime();
                if (now - c.lastMelee >= meleeCooldown
                        && near.getLocation().distance(c.entity.getLocation()) <= meleeReach) {
                    c.lastMelee = now;
                    if (c.model != null) c.model.playAttack();
                    c.entity.getWorld().playSound(c.entity.getLocation(),
                            Sound.ENTITY_PLAYER_ATTACK_STRONG, SoundCategory.HOSTILE, 1.0f, 0.8f);
                    hurt(c.entity, near, meleeDamage);
                }
            }

            if (--c.jumpTimer <= 0) {
                c.jumpTimer = 200;      // 10 saniye
                jump(c);
            }

            // Havadayken yere degme anini yakala.
            if (c.airborne && c.entity.isOnGround()) {
                c.airborne = false;
                impact(c);
            }
        }
    }

    private Player nearest(Location at) {
        Player best = null;
        double bestD = Double.MAX_VALUE;
        for (Player p : at.getWorld().getPlayers()) {
            if (p.getGameMode() == org.bukkit.GameMode.SPECTATOR) continue;
            if (p.getGameMode() == org.bukkit.GameMode.CREATIVE) continue;
            double d = p.getLocation().distanceSquared(at);
            if (d < bestD && d < 40 * 40) { bestD = d; best = p; }
        }
        return best;
    }

    /** Klon havaya siçrar; indiginde r=5 alanda hasar verip geri savurur. */
    private void jump(Clone c) {
        Location at = c.entity.getLocation();
        c.entity.setVelocity(new Vector(0, 0.62, 0));
        c.airborne = true;
        if (c.model != null) c.model.playSlamRaise();
        at.getWorld().playSound(at, Sound.ENTITY_RAVAGER_STEP, SoundCategory.HOSTILE, 1.2f, 0.6f);
    }

    private void impact(Clone c) {
        Location at = c.entity.getLocation();
        World w = at.getWorld();

        if (c.model != null) c.model.playSlamStrike();
        w.playSound(at, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.HOSTILE, 1.0f, 1.5f);
        w.spawnParticle(Particle.BLOCK, at, 40, 1.6, 0.2, 1.6, 0.1,
                w.getBlockAt(at.clone().subtract(0, 1, 0)).getBlockData());

        for (Player p : w.getPlayers()) {
            if (p.getGameMode() == org.bukkit.GameMode.SPECTATOR) continue;
            double d = p.getLocation().distance(at);
            if (d > 5.0) continue;

            // Darbe hasari Overlord'un yere cakilmasiyla ayni; itis daha zayif.
            hurt(c.entity, p, jumpDamage);

            Vector push = p.getLocation().toVector().subtract(at.toVector());
            if (push.lengthSquared() < 0.01) push = new Vector(0, 0, 1);
            push.setY(0).normalize().multiply(knockback * (1.0 - d / 6.5));
            push.setY(0.42);
            p.setVelocity(p.getVelocity().add(push));
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 0.9f, 0.8f);
        }
    }

    /** Overlord'unkinden zayif itis gucu. */
    private double knockback = 1.1;
    /** Yakin dovus hasari. */
    private double meleeDamage = 10.0;
    private double meleeReach = 4.0;
    private int meleeCooldown = 20;
    /** Ziplama darbesi -- Overlord'un yere cakilmasiyla ayni. */
    private double jumpDamage = 26.0;

    public void setKnockback(double v)   { this.knockback = v; }
    public void setMelee(double dmg, double reach, int cooldownTicks) {
        this.meleeDamage = dmg;
        this.meleeReach = reach;
        this.meleeCooldown = Math.max(1, cooldownTicks);
    }
    public void setJumpDamage(double v)  { this.jumpDamage = v; }

    /**
     * Klonun oyuncuya verdigi hasar. Bossla ayni mantik: MAGIC turu zirhi
     * deler, yani config'teki sayi dogrudan cana isler.
     */
    private void hurt(Giant from, Player p, double amount) {
        if (amount <= 0) return;
        DamageSource src = DamageSource.builder(DamageType.MAGIC)
                .withCausingEntity(from)
                .withDirectEntity(from)
                .build();
        p.damage(amount, src);
    }

    // ==================== TEMIZLIK ====================

    public int size() {
        return clones.size();
    }

    public boolean any() {
        return !clones.isEmpty();
    }

    public void removeAll() {
        for (Clone c : clones) {
            if (c.model != null) c.model.remove();
            if (!c.entity.isDead()) c.entity.remove();
        }
        clones.clear();
    }

    public static void cleanupLeftovers(World w) {
        if (w == null) return;
        for (Entity e : w.getEntities()) {
            if (e.getScoreboardTags().contains(TAG)) e.remove();
        }
    }
}
