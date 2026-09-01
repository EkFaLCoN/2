package net.cursedvalley.mobs;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Lizard Dragon -- Cursed Valley'in ates ejderi.
 *
 * Neden Ender Dragon govdesi: 26.2'de bir varliga resourcepack ile ozel model
 * atamanin vanilla yolu yok. Overlord'da bunu 7 item_display kemikle cozduk ama
 * ucan, kanat cirpan bir ejder icin o yol hem cok parca hem cok tick maliyeti
 * demek. Ender Dragon zaten gercek bir ejder modeli, uçuyor ve vanilla AI'si
 * 0,0 etrafinda donuyor -- arena tam orada oldugu icin birebir oturuyor.
 *
 * Cani vanilla degil: 200.000 can nitelik siniri olan 1024'un cok uzerinde,
 * bu yuzden hasar olaylari iptal edilip can BossState'te elle tutuluyor.
 *
 * Blok kirma tamamen kapali (bkz. ana siniftaki olay dinleyicileri).
 */
public final class LizardDragon {

    public static final String TAG = "cv_dragon";

    private final JavaPlugin plugin;

    private EnderDragon dragon;
    private BossState state;
    private BossBar bar;

    // --- ayarlar ---
    private double maxHealth = 200_000;
    private int spawnRadius = 40;
    private double hoverHeight = 14.0;
    /** Arena zemin yuksekligi (kristalin Y'si). Yeraltinda oldugu icin sart. */
    private double arenaY = 0;
    private int abilityInterval = 9;          // saniye
    private double breathDamage = 20, breathRange = 22, breathAngle = 40;
    private double fireballDamage = 16, fireballRadius = 3.5;
    private int fireballCount = 14;
    private double gustDamage = 12, gustRadius = 13, gustKnockback = 2.1;
    private double tailDamage = 24, tailRadius = 9;
    private int fireTicks = 100;

    private int tickCounter;
    private int abilityCounter;
    private boolean enraged;
    private boolean casting;

    public LizardDragon(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void setArenaY(double y) { this.arenaY = y; }

    public void configure(double maxHealth, int spawnRadius, double hoverHeight,
                          int abilityInterval, double breathDamage, double breathRange,
                          double breathAngle, double fireballDamage, double fireballRadius,
                          int fireballCount, double gustDamage, double gustRadius,
                          double gustKnockback, double tailDamage, double tailRadius,
                          int fireTicks) {
        this.maxHealth = maxHealth;
        this.spawnRadius = Math.max(5, spawnRadius);
        this.hoverHeight = hoverHeight;
        this.abilityInterval = Math.max(2, abilityInterval);
        this.breathDamage = breathDamage;
        this.breathRange = breathRange;
        this.breathAngle = breathAngle;
        this.fireballDamage = fireballDamage;
        this.fireballRadius = fireballRadius;
        this.fireballCount = fireballCount;
        this.gustDamage = gustDamage;
        this.gustRadius = gustRadius;
        this.gustKnockback = gustKnockback;
        this.tailDamage = tailDamage;
        this.tailRadius = tailRadius;
        this.fireTicks = fireTicks;
    }

    public boolean alive() {
        return dragon != null && !dragon.isDead();
    }

    public boolean is(Entity e) {
        return e != null && dragon != null && e.getUniqueId().equals(dragon.getUniqueId());
    }

    // ==================== DOGUS ====================

    /** Ejderi 0,0'in {@code spawnRadius} blok icinde rasgele bir noktada dogurur. */
    public void spawn(World w) {
        remove();

        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        double a = rnd.nextDouble() * Math.PI * 2;
        double r = spawnRadius * Math.sqrt(rnd.nextDouble());   // alana esit dagilim
        Location at = new Location(w, Math.cos(a) * r, arenaY, Math.sin(a) * r);
        // Arena yeraltinda; yuzey degil, arena zemini referans alinir.
        at = Ground.hoverNear(at, arenaY, hoverHeight);

        // Dogum sirasinda SADECE etiket verilir.
        // getBossBar() ve setPhase() varlik dunyaya EKLENDIKTEN sonra calisir;
        // spawn geri cagriminda cagrilirsa istisna firlatir.
        dragon = w.spawn(at, EnderDragon.class, d -> d.addScoreboardTag(TAG));

        try {
            dragon.setPersistent(true);
            // HOVER, portal/end savasi gerektirmez -- overworld icin guvenli faz.
            dragon.setPhase(EnderDragon.Phase.HOVER);
        } catch (Exception ex) {
            plugin.getLogger().warning("Ejder fazı ayarlanamadı: " + ex.getMessage());
        }

        try {
            var mx = dragon.getAttribute(Attribute.MAX_HEALTH);
            if (mx != null) mx.setBaseValue(1024.0);
            dragon.setHealth(1024.0);
        } catch (Exception ex) {
            plugin.getLogger().warning("Ejder canı ayarlanamadı: " + ex.getMessage());
        }

        try {
            if (dragon.getBossBar() != null) dragon.getBossBar().setVisible(false);
        } catch (Exception ex) {
            plugin.getLogger().warning("Vanilla ejder barı gizlenemedi: " + ex.getMessage());
        }

        state = new BossState("LIZARD DRAGON", maxHealth, true);
        enraged = false;
        casting = false;
        abilityCounter = 0;

        bar = Bukkit.createBossBar("", BarColor.RED, BarStyle.SEGMENTED_10);
        bar.setVisible(true);
        refreshBar();

        w.playSound(at, Sound.ENTITY_ENDER_DRAGON_GROWL, SoundCategory.HOSTILE, 4.0f, 0.6f);
        Bukkit.broadcast(Component.text("Lizard Dragon Cursed Valley semalarında belirdi!",
                NamedTextColor.RED));
    }

    public void remove() {
        if (bar != null) {
            bar.removeAll();
            bar = null;
        }
        if (dragon != null && !dragon.isDead()) dragon.remove();
        dragon = null;
        state = null;
    }

    public static void cleanupLeftovers(World w) {
        if (w == null) return;
        for (Entity e : w.getEntities()) {
            if (e.getScoreboardTags().contains(TAG)) e.remove();
        }
    }

    // ==================== CAN ====================

    /**
     * Ejdere hasar uygular.
     *
     * @return oldu ise vuranlarin listesi, olmedi ise null
     */
    public List<UUID> hit(Player attacker, double damage) {
        if (state == null || dragon == null) return null;

        dragon.getWorld().spawnParticle(Particle.FLAME,
                dragon.getLocation(), 8, 1.0, 1.0, 1.0, 0.02);

        boolean died = state.hit(attacker, damage);
        refreshBar();

        // Can yarilandiginda kalici ofke: yetenekler daha sik gelir.
        if (!enraged && state.health() <= state.maxHealth() * 0.5) {
            enraged = true;
            Bukkit.broadcast(Component.text("Lizard Dragon öfkelendi — alevleri güçleniyor!",
                    NamedTextColor.GOLD));
            dragon.getWorld().playSound(dragon.getLocation(),
                    Sound.ENTITY_ENDER_DRAGON_GROWL, SoundCategory.HOSTILE, 4.0f, 0.4f);
        }

        if (died) {
            List<UUID> hitters = new ArrayList<>(state.attackers().keySet());
            Location at = dragon.getLocation();
            at.getWorld().playSound(at, Sound.ENTITY_ENDER_DRAGON_DEATH,
                    SoundCategory.HOSTILE, 4.0f, 0.7f);
            at.getWorld().spawnParticle(Particle.EXPLOSION_EMITTER, at, 12, 3, 3, 3, 0);
            remove();
            return hitters;
        }
        return null;
    }

    private void refreshBar() {
        if (bar == null || state == null) return;
        double ratio = Math.max(0, Math.min(1, state.health() / state.maxHealth()));
        bar.setProgress(ratio);
        bar.setTitle("§cLizard Dragon  §f" + fmt(state.health()) + " / " + fmt(state.maxHealth()));
    }

    private static String fmt(double v) {
        return String.format("%,d", Math.round(v)).replace(',', '.');
    }

    // ==================== DONGU ====================

    /** Saniyede bir: can bari izleyicileri, yenilenme, yetenek sayaci. */
    public void everySecond(double regenPerTick, int regenSeconds) {
        if (!alive() || state == null) return;

        // Vanilla cani hep dolu tut; gercek can bizde.
        if (dragon.getHealth() < 1024.0) dragon.setHealth(1024.0);

        tickCounter++;

        // Bar yalnizca yakindakilere gorunur.
        if (bar != null) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                boolean near = p.getWorld().equals(dragon.getWorld())
                        && p.getLocation().distance(dragon.getLocation()) <= 90;
                if (near && !bar.getPlayers().contains(p)) bar.addPlayer(p);
                else if (!near && bar.getPlayers().contains(p)) bar.removePlayer(p);
            }
        }

        if (tickCounter % Math.max(1, regenSeconds) == 0) {
            state.heal(regenPerTick);
            refreshBar();
        }

        if (tickCounter % 8 == 0) {
            dragon.getWorld().playSound(dragon.getLocation(),
                    Sound.ENTITY_ENDER_DRAGON_AMBIENT, SoundCategory.HOSTILE, 3.0f, 0.7f);
        }

        int interval = enraged ? Math.max(2, abilityInterval - 3) : abilityInterval;
        if (!casting && ++abilityCounter >= interval) {
            abilityCounter = 0;
            useAbility();
        }
    }

    // ==================== YETENEKLER ====================

    private void useAbility() {
        List<Player> targets = nearbyPlayers(60);
        if (targets.isEmpty()) return;

        // Yakinda oyuncu varsa kuyruk savurma daha olasi; uzaktalarsa ates topu.
        Player closest = targets.get(0);
        double d = closest.getLocation().distance(dragon.getLocation());

        int roll = ThreadLocalRandom.current().nextInt(100);
        if (d < tailRadius && roll < 45) tailSweep();
        else if (roll < 40) fireBreath(closest);
        else if (roll < 75) fireballRain();
        else wingGust();
    }

    private List<Player> nearbyPlayers(double range) {
        List<Player> out = new ArrayList<>();
        Location at = dragon.getLocation();
        for (Player p : at.getWorld().getPlayers()) {
            if (p.getGameMode() == GameMode.SPECTATOR || p.getGameMode() == GameMode.CREATIVE) continue;
            if (p.getLocation().distance(at) > range) continue;
            out.add(p);
        }
        out.sort((x, y) -> Double.compare(
                x.getLocation().distanceSquared(at), y.getLocation().distanceSquared(at)));
        return out;
    }

    /**
     * Alev Nefesi: hedefe dogru koni seklinde ates puskurtur.
     *
     * Koninin disina cikan kurtulur -- yani yandan kacilabilir. Zemine ates
     * BIRAKMAZ, sadece degdigi oyuncuyu yakar; harita zarar gormez.
     */
    private void fireBreath(Player target) {
        casting = true;
        Location origin = dragon.getLocation().clone();
        Vector dir = target.getLocation().toVector()
                .subtract(origin.toVector()).normalize();

        World w = origin.getWorld();
        w.playSound(origin, Sound.ENTITY_ENDER_DRAGON_SHOOT, SoundCategory.HOSTILE, 3.5f, 0.7f);
        Bukkit.broadcast(Component.text("Lizard Dragon nefesini topluyor...", NamedTextColor.GOLD));

        new org.bukkit.scheduler.BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                if (!alive() || t > 40) {
                    casting = false;
                    cancel();
                    return;
                }
                t++;

                // Ilk 15 tick uyari: agzinda alev toplanir.
                if (t <= 15) {
                    w.spawnParticle(Particle.FLAME, origin.clone().add(dir.clone().multiply(2)),
                            12, 0.4, 0.4, 0.4, 0.02);
                    return;
                }

                // Koninin izi
                for (double dist = 2; dist < breathRange; dist += 1.2) {
                    Location p = origin.clone().add(dir.clone().multiply(dist));
                    w.spawnParticle(Particle.FLAME, p, 4, dist * 0.05, dist * 0.05, dist * 0.05, 0.01);
                    w.spawnParticle(Particle.SMOKE, p, 1, 0.2, 0.2, 0.2, 0.01);
                }

                if (t % 5 != 0) return;   // hasar saniyede birkac kez

                for (Player p : w.getPlayers()) {
                    if (p.getGameMode() == GameMode.SPECTATOR) continue;
                    Vector to = p.getLocation().toVector().subtract(origin.toVector());
                    double dist = to.length();
                    if (dist > breathRange || dist < 0.5) continue;

                    double ang = Math.toDegrees(dir.angle(to.normalize()));
                    if (ang > breathAngle) continue;   // koninin disinda

                    hurt(p, breathDamage);
                    p.setFireTicks(Math.max(p.getFireTicks(), fireTicks));
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    /**
     * Ates Yagmuru: gokten alev toplari duser. Her biri once yerde bir uyari
     * cemberi gosterir. Patlama gercek explosion DEGIL -- blok kirilmaz,
     * zemin tutusmaz.
     */
    private void fireballRain() {
        Location center = dragon.getLocation().clone();
        World w = center.getWorld();
        w.playSound(center, Sound.ENTITY_ENDER_DRAGON_FLAP, SoundCategory.HOSTILE, 3.0f, 0.6f);
        Bukkit.broadcast(Component.text("Gökten alev yağıyor!", NamedTextColor.RED));

        int count = enraged ? (int) (fireballCount * 1.5) : fireballCount;
        ThreadLocalRandom rnd = ThreadLocalRandom.current();

        for (int i = 0; i < count; i++) {
            double a = rnd.nextDouble() * Math.PI * 2;
            double r = 25 * Math.sqrt(rnd.nextDouble());
            Location target = Ground.findNear(
                    center.clone().add(Math.cos(a) * r, 0, Math.sin(a) * r), center.getY());

            plugin.getServer().getScheduler().runTaskLater(plugin,
                    () -> dropFireball(target), i * 4L);
        }
    }

    private void dropFireball(Location target) {
        World w = target.getWorld();

        new org.bukkit.scheduler.BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                t++;

                // Uyari cemberi
                if (t <= 20) {
                    for (int i = 0; i < 16; i++) {
                        double a = (2 * Math.PI * i) / 16;
                        w.spawnParticle(Particle.FLAME, target.clone().add(
                                Math.cos(a) * fireballRadius, 0.2, Math.sin(a) * fireballRadius),
                                1, 0, 0, 0, 0);
                    }
                    return;
                }

                // Dusus izi
                if (t <= 32) {
                    double h = (32 - t) * 2.0;
                    w.spawnParticle(Particle.FLAME, target.clone().add(0, h, 0), 10, 0.3, 0.3, 0.3, 0.03);
                    return;
                }

                // Carpma: elle hasar, gercek patlama yok
                w.playSound(target, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.HOSTILE, 1.4f, 1.2f);
                w.spawnParticle(Particle.EXPLOSION, target.clone().add(0, 1, 0), 3, 0.6, 0.6, 0.6, 0);
                w.spawnParticle(Particle.LAVA, target, 20, 1.2, 0.4, 1.2, 0);

                for (Player p : w.getPlayers()) {
                    if (p.getGameMode() == GameMode.SPECTATOR) continue;
                    double dist = p.getLocation().distance(target);
                    if (dist > fireballRadius) continue;
                    hurt(p, fireballDamage * (1.0 - dist / (fireballRadius * 1.6)));
                    p.setFireTicks(Math.max(p.getFireTicks(), fireTicks / 2));
                }
                cancel();
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    /** Kanat Firtinasi: cevredekileri havalandirip geriye savurur. */
    private void wingGust() {
        Location at = dragon.getLocation();
        World w = at.getWorld();
        w.playSound(at, Sound.ENTITY_ENDER_DRAGON_FLAP, SoundCategory.HOSTILE, 4.0f, 0.8f);
        w.spawnParticle(Particle.CLOUD, at, 60, 4, 2, 4, 0.2);

        for (Player p : w.getPlayers()) {
            if (p.getGameMode() == GameMode.SPECTATOR) continue;
            double d = p.getLocation().distance(at);
            if (d > gustRadius) continue;

            hurt(p, gustDamage);
            Vector push = p.getLocation().toVector().subtract(at.toVector());
            if (push.lengthSquared() < 0.01) push = new Vector(0, 0, 1);
            push.setY(0).normalize().multiply(gustKnockback);
            push.setY(0.75);
            p.setVelocity(p.getVelocity().add(push));
        }
    }

    /** Kuyruk Savurma: cok yakindakilere agir hasar, hizli ve uyarisiz. */
    private void tailSweep() {
        Location at = dragon.getLocation();
        World w = at.getWorld();
        w.playSound(at, Sound.ENTITY_ENDER_DRAGON_HURT, SoundCategory.HOSTILE, 2.6f, 1.3f);

        for (int i = 0; i < 40; i++) {
            double a = (2 * Math.PI * i) / 40;
            w.spawnParticle(Particle.SWEEP_ATTACK, at.clone().add(
                    Math.cos(a) * tailRadius, 0.5, Math.sin(a) * tailRadius), 1, 0, 0, 0, 0);
        }

        for (Player p : w.getPlayers()) {
            if (p.getGameMode() == GameMode.SPECTATOR) continue;
            double d = p.getLocation().distance(at);
            if (d > tailRadius) continue;

            hurt(p, tailDamage);
            Vector push = p.getLocation().toVector().subtract(at.toVector());
            if (push.lengthSquared() < 0.01) push = new Vector(0, 0, 1);
            push.setY(0).normalize().multiply(1.4);
            push.setY(0.4);
            p.setVelocity(p.getVelocity().add(push));
        }
    }

    /** MAGIC turu zirhi deler; config'teki sayi dogrudan cana isler. */
    private void hurt(Player p, double amount) {
        if (amount <= 0 || dragon == null) return;
        DamageSource src = DamageSource.builder(DamageType.MAGIC)
                .withCausingEntity(dragon)
                .withDirectEntity(dragon)
                .build();
        p.damage(amount, src);
    }

    // ==================== BILGI ====================

    public Location location() {
        return dragon == null ? null : dragon.getLocation();
    }

    public double health() {
        return state == null ? 0 : state.health();
    }

    public double maxHealthValue() {
        return state == null ? maxHealth : state.maxHealth();
    }
}
