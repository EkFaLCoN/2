package net.cursedvalley.mobs;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Giant;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * CursedValleyMobsAndMob — Cursed Valley bosslari.
 *
 * Akis: Lanetli Kristal dogar -> kirilinca Overlord cikar -> Overlord olunce
 * 3 saatlik bekleme baslar, sure dolunca kristal yeniden dogar.
 *
 * CursedValleyCore ve CursedValleyRules'a dokunmaz.
 */
public final class CursedValleyMobsAndMob extends JavaPlugin implements Listener {

    // --- ayarlar ---
    private String worldName;
    private int crystalX, crystalY, crystalZ;
    private long cooldownMillis;
    private double crystalMaxHp, crystalRegen;
    private double overlordMaxHp, overlordRegen;
    private int regenSeconds;

    private double attackTolerance;
    private double meleeDamage, meleeReach;
    private int meleeCooldownTicks;

    private int abilityInterval, abilityWarn;
    private double abilityRadius, abilityDamage, abilityKnockback, abilityJumpHeight;
    private double leashRadius, detectRadius;
    private double resetRegen;
    private int resetRegenSeconds;

    /** Overlord yuvasina donuyor mu (bu haldeyken oyunculari gormezden gelir). */
    private boolean resetting;
    /** Donus modunda yuvaya varildi mi. */
    private boolean homeReached;

    /** Hedef degistirme sayaci (saniye). */
    private int targetSwitch;
    private int targetSwitchSeconds;
    private int meteorMin, meteorMax, meteorWarnTicks, meteorFireTicks;
    private double meteorRadius, meteorDamage, meteorHitRadius;

    private final DropRegistry drops = new DropRegistry();
    private final LootChest lootChest = new LootChest(this);
    private final CloneSquad clones = new CloneSquad(this);
    private final LizardDragon dragon = new LizardDragon(this);
    private double dragonRegen;
    private int dragonRegenSeconds;

    /** Faz ayarlari. */
    private int cloneCount;
    private double cloneHealth, cloneScale, cloneKnockback;
    private double stormRadius, stormDamage, stormKnockback;
    private double diveDamage, diveRadius;
    private int unseenResetSeconds;
    /** Kac saniyedir hicbir oyuncu bossu gormedi. */
    private int unseenSeconds;
    /** Yeraltina dalis sirasinda melee/yetenek kapali. */
    private boolean diving;
    /** Gurz Firtinasi + sersemleme suresince diger yetenekler ve melee durur. */
    private boolean stormLock;

    /** Ganimet sandigi ayarlari: boss ve siradan yaratik icin ayri stil. */
    private boolean lootChestEnabled;
    private boolean mobChestEnabled;
    private LootChest.Style bossChestStyle;
    private LootChest.Style mobChestStyle;

    /** Overlord'un ozel modeli (item_display kemikleri). */
    private final OverlordModel model = new OverlordModel(this);
    private boolean customModel;

    // --- canli durum ---
    private EnderCrystal crystal;
    private BossState crystalState;
    private Entity crystalBar;

    private Giant overlord;
    private BossState overlordState;

    private long nextSpawnAt;                 // epoch ms
    private int tickCounter, abilityCounter;
    private int hurtSoundCooldown;

    /** Meteor cagirma duruşu suresi (1 saniye). */
    private static final int CAST_TICKS = 20;

    /** Cagirma sirasinda boss durur, yakin dovus yapmaz. */
    private boolean casting;

    /** Yakin dovus icin oyuncu basina son vurus zamani (dunya tick'i). */
    private final Map<UUID, Long> lastMeleeTick = new HashMap<>();

    /** Macro tespiti: oyuncu basina son gecerli vurus zamani (ms). */
    private final Map<UUID, Long> lastHit = new HashMap<>();

    private static final String TAG = "cv_boss";

    /**
     * Config surumu. Bu sayi kod tarafinda artirildiginda sunucudaki eski
     * config.yml otomatik yenilenir.
     *
     * Neden gerekli: Bukkit, dosya zaten varsa yeni varsayilanlari UZERINE YAZMAZ.
     * Yeni bir ayar eklendiginde ya da bir varsayilan degistiginde sunucuda eski
     * deger okunmaya devam ediyordu (meteor sayisinin 11'de kalmasi bu yuzdendi).
     */
    private static final int CONFIG_VERSION = 13;

    // ==================== ACILIS ====================

    @Override
    public void onEnable() {
        saveDefaultConfig();
        migrateConfig();
        loadSettings();
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(lootChest, this);
        cleanupLeftovers();

        getServer().getScheduler().runTaskTimer(this, this::everySecond, 20L, 20L);
        getServer().getScheduler().runTaskTimer(this, this::meleeTick, 10L, 10L);
        getServer().getScheduler().runTaskTimer(this, this::modelTick, 1L, 1L);
        getServer().getScheduler().runTaskTimer(this, lootChest::tick, 1L, 1L);
        getServer().getScheduler().runTaskTimer(this, clones::tick, 1L, 1L);
        getServer().getScheduler().runTaskTimer(this, dragon::tick, 1L, 1L);

        getLogger().info("CursedValleyMobsAndMob v" + getPluginMeta().getVersion()
                + " | macro koruması: kristal + tüm yaratıklar AÇIK");
        getLogger().info("CursedValleyMobsAndMob etkin — dünya: " + worldName
                + " | kristal: " + crystalX + "/" + crystalY + "/" + crystalZ
                + " | bekleme: " + (cooldownMillis / 3600000.0) + " saat");
    }

    @Override
    public void onDisable() {
        lootChest.removeAll();
        clones.removeAll();
        dragon.remove();
        removeCrystal();
        model.remove();
        if (overlord != null && !overlord.isDead()) overlord.remove();
    }

    /**
     * Eski config.yml'yi yenisiyle degistirir; drop listeleri ve bekleme suresi korunur.
     * Eski dosya config-eski.yml adiyla saklanir.
     */
    private void migrateConfig() {
        int found = getConfig().getInt("config-version", 0);
        if (found >= CONFIG_VERSION) return;

        // Kaybolmamasi gerekenler
        drops.load(getConfig());
        String oldWorld = getConfig().getString("world", "cursedvalley");
        long oldNext = getConfig().getLong("next-spawn-epoch", 0L);

        java.io.File current = new java.io.File(getDataFolder(), "config.yml");
        java.io.File backup = new java.io.File(getDataFolder(), "config-eski.yml");
        if (backup.exists() && !backup.delete()) {
            getLogger().warning("Eski yedek silinemedi: " + backup.getName());
        }
        if (current.exists() && !current.renameTo(backup)) {
            getLogger().warning("config.yml yedeklenemedi, göç atlandı.");
            return;
        }

        saveResource("config.yml", false);
        reloadConfig();

        getConfig().set("world", oldWorld);
        getConfig().set("next-spawn-epoch", oldNext);
        drops.save(getConfig());
        saveConfig();

        getLogger().warning("config.yml v" + found + " -> v" + CONFIG_VERSION
                + " olarak yenilendi. Eski dosya: config-eski.yml"
                + " (drop listeleri ve dünya adı korundu).");
    }

    private void loadSettings() {
        reloadConfig();
        var c = getConfig();
        worldName      = c.getString("world", "cursedvalley");
        cloneCount     = c.getInt("overlord.ability.phase.clones.count", 10);
        cloneHealth    = c.getDouble("overlord.ability.phase.clones.health", 500);
        cloneScale     = c.getDouble("overlord.ability.phase.clones.scale", 1.6);
        cloneKnockback = c.getDouble("overlord.ability.phase.clones.knockback", 1.1);
        clones.setKnockback(cloneKnockback);
        clones.setMelee(
                c.getDouble("overlord.ability.phase.clones.melee-damage", 5.0),
                c.getDouble("overlord.melee-reach", 4.0),
                Math.max(5, c.getInt("overlord.melee-cooldown-ticks", 20)));
        // Ziplama darbesi Overlord'un yere cakilmasiyla ayni hasari verir.
        clones.setJumpDamage(c.getDouble("overlord.ability.damage", 26));
        clones.setSight(c.getDouble("overlord.ability.phase.clones.sight-range", 7.0));
        clones.setSpeed(c.getDouble("overlord.ability.phase.clones.speed", 0.75));

        stormRadius    = c.getDouble("overlord.ability.phase.storm.radius", 8.0);
        stormDamage    = c.getDouble("overlord.ability.phase.storm.damage", 18.0);
        stormKnockback = c.getDouble("overlord.ability.phase.storm.knockback", 1.7);

        diveDamage     = c.getDouble("overlord.ability.phase.dive.damage", 22.0);
        diveRadius     = c.getDouble("overlord.ability.phase.dive.radius", 4.5);

        unseenResetSeconds = Math.max(5, c.getInt("overlord.unseen-reset-seconds", 30));

        dragon.configure(
                c.getDouble("dragon.max-health", 70_000),
                c.getInt("dragon.spawn-radius", 40),
                0.0,   // kullanilmiyor: ejder ucmaz
                c.getInt("dragon.ability-interval", 9),
                c.getDouble("dragon.breath.damage", 20),
                c.getDouble("dragon.breath.range", 22),
                c.getDouble("dragon.breath.angle", 40),
                c.getDouble("dragon.fireball.damage", 16),
                c.getDouble("dragon.fireball.radius", 3.5),
                c.getInt("dragon.fireball.count", 14),
                c.getDouble("dragon.gust.damage", 12),
                c.getDouble("dragon.gust.radius", 13),
                c.getDouble("dragon.gust.knockback", 2.1),
                c.getDouble("dragon.tail.damage", 24),
                c.getDouble("dragon.tail.radius", 9),
                c.getInt("dragon.fire-ticks", 100));
        // Ejder arenanin zemin yuksekligini bilmeli -- bowl yeraltinda.
        dragon.setArenaY(c.getDouble("dragon.arena-y", crystalY));
        dragon.setBite(
                c.getDouble("dragon.bite.damage", 18),
                c.getDouble("dragon.bite.reach", 6.0),
                c.getInt("dragon.bite.cooldown-ticks", 22));
        dragon.setSight(c.getDouble("dragon.sight-range", 40));
        dragon.setLeash(null, c.getDouble("overlord.leash-radius", 50.0));
        dragonRegen        = c.getDouble("dragon.regen-amount", 300);
        dragonRegenSeconds = Math.max(1, c.getInt("dragon.regen-seconds", 10));

        lootChestEnabled = c.getBoolean("loot-chest.boss.enabled", true);
        bossChestStyle = new LootChest.Style(
                c.getString("loot-chest.boss.texture", ""),
                c.getString("loot-chest.boss.title", "Ganimet Sandığı"),
                c.getInt("loot-chest.boss.seconds", 30));

        mobChestEnabled = c.getBoolean("loot-chest.mob.enabled", true);
        mobChestStyle = new LootChest.Style(
                c.getString("loot-chest.mob.texture", ""),
                c.getString("loot-chest.mob.title", "Ganimet Kutusu"),
                c.getInt("loot-chest.mob.seconds", 30));
        lootChest.resetCache();
        crystalX       = c.getInt("crystal.x", 0);
        crystalY       = c.getInt("crystal.y", -47);
        crystalZ       = c.getInt("crystal.z", 0);
        cooldownMillis = (long) (c.getDouble("respawn-hours", 3.0) * 3600000L);
        crystalMaxHp   = c.getDouble("crystal.max-health", 50000);
        crystalRegen   = c.getDouble("crystal.regen-amount", 200);
        overlordMaxHp  = c.getDouble("overlord.max-health", 100000);
        overlordRegen  = c.getDouble("overlord.regen-amount", 200);
        regenSeconds   = Math.max(1, c.getInt("regen-seconds", 10));

        attackTolerance = c.getDouble("attack-tolerance", 0.85);
        customModel     = c.getBoolean("overlord.custom-model", true);

        meleeDamage        = c.getDouble("overlord.melee-damage", 14);
        meleeReach         = c.getDouble("overlord.melee-reach", 4.0);
        meleeCooldownTicks = Math.max(5, c.getInt("overlord.melee-cooldown-ticks", 20));

        abilityInterval = Math.max(1, c.getInt("overlord.ability.interval-seconds", 10));
        abilityWarn     = Math.max(1, c.getInt("overlord.ability.warn-seconds", 1));
        abilityRadius   = c.getDouble("overlord.ability.radius", 10);
        abilityDamage   = c.getDouble("overlord.ability.damage", 26);
        abilityKnockback = c.getDouble("overlord.ability.knockback-multiplier", 2.3);

        abilityJumpHeight = c.getDouble("overlord.ability.jump-height", 10.0);

        leashRadius   = c.getDouble("overlord.leash-radius", 50.0);
        detectRadius  = c.getDouble("overlord.detect-radius", 23.0);
        resetRegen        = c.getDouble("overlord.reset-regen-amount", 600.0);
        resetRegenSeconds = Math.max(1, c.getInt("overlord.reset-regen-seconds", 5));
        targetSwitchSeconds = Math.max(2, c.getInt("overlord.target-switch-seconds", 8));

        meteorMin       = c.getInt("overlord.ability.meteor.count-min", 25);
        meteorMax       = c.getInt("overlord.ability.meteor.count-max", 25);
        meteorRadius    = c.getDouble("overlord.ability.meteor.radius", 20.0);
        meteorDamage    = c.getDouble("overlord.ability.meteor.damage", 13.0);
        meteorHitRadius = c.getDouble("overlord.ability.meteor.hit-radius", 2.0);
        meteorWarnTicks = c.getInt("overlord.ability.meteor.warn-ticks", 25);
        meteorFireTicks = c.getInt("overlord.ability.meteor.fire-ticks", 80);

        nextSpawnAt = c.getLong("next-spawn-epoch", 0L);
        drops.load(c);
    }

    private World world() {
        return Bukkit.getWorld(worldName);
    }

    private void cleanupLeftovers() {
        World w = world();
        if (w == null) return;
        for (Entity e : w.getEntities()) {
            if (e.getScoreboardTags().contains(TAG)) e.remove();
        }
        OverlordModel.cleanupLeftovers(w);
        LootChest.cleanupLeftovers(w);
        CloneSquad.cleanupLeftovers(w);
        LizardDragon.cleanupLeftovers(w);
    }

    /** Her tick: modelin kemiklerini bossun uzerinde tut ve animasyonu isle. */
    private void modelTick() {
        if (!customModel) return;
        if (overlord == null || overlord.isDead()) return;
        model.tick(overlord);
    }

    private void setNextSpawn(long epochMs) {
        nextSpawnAt = epochMs;
        getConfig().set("next-spawn-epoch", epochMs);
        saveConfig();
    }

    // ==================== KRISTAL ====================

    private void spawnCrystal() {
        World w = world();
        if (w == null) {
            getLogger().warning("Dünya bulunamadı: " + worldName);
            return;
        }
        if (crystal != null && !crystal.isDead()) return;
        if (overlord != null && !overlord.isDead()) return;

        Location loc = new Location(w, crystalX + 0.5, crystalY, crystalZ + 0.5);

        Chunk chunk = loc.getChunk();
        chunk.load();
        chunk.addPluginChunkTicket(this);

        crystal = w.spawn(loc, EnderCrystal.class, c -> {
            c.setShowingBottom(true);
            c.setInvulnerable(false);
            c.addScoreboardTag(TAG);
            c.setPersistent(true);
        });
        crystalState = new BossState("KRİSTAL", crystalMaxHp);
        lastHit.clear();

        crystalBar = w.spawn(loc.clone().add(0, 2.2, 0), org.bukkit.entity.ArmorStand.class, a -> {
            a.setMarker(true);
            a.setInvisible(true);
            a.setInvulnerable(true);
            a.setGravity(false);
            a.setCustomNameVisible(true);
            a.addScoreboardTag(TAG);
        });
        crystalBar.customName(crystalState.bar());

        w.playSound(loc, Sound.ENTITY_ENDER_DRAGON_GROWL, 1.0f, 0.8f);
        Bukkit.broadcast(Component.text("Lanetli Kristal vadide belirdi!", NamedTextColor.LIGHT_PURPLE));
    }

    private void removeCrystal() {
        if (crystalBar != null && !crystalBar.isDead()) crystalBar.remove();
        if (crystal != null && !crystal.isDead()) crystal.remove();
        crystalBar = null;
        crystal = null;
        crystalState = null;
    }

    private void crystalDied() {
        Location loc = crystal.getLocation();
        World w = loc.getWorld();

        List<UUID> hitters = new ArrayList<>(crystalState.attackers().keySet());
        removeCrystal();

        w.createExplosion(loc, 0.0f, false, false);
        w.playSound(loc, Sound.ENTITY_GENERIC_EXPLODE, 1.0f, 0.7f);

        giveDrops("crystal", hitters, loc, "Lanetli Kristal");
        spawnOverlord(loc);
    }

    /**
     * Macro / autoclicker tespiti.
     *
     * DIKKAT: burada getAttackCooldown() KULLANILAMAZ. Hasar olayi tetiklendiginde
     * oyun saldiri gostergesini coktan sifirlamis olur, yani deger her zaman ~0
     * doner ve normal vuruslar da macro sanilir.
     *
     * Onun yerine iki vurus arasindaki gercek sureye bakiyoruz. Beklenen sure
     * oyuncunun saldiri hizi ozelliginden gelir (hiz 4.0 -> 250 ms, 1.6 -> 625 ms),
     * yani hangi silahi tuttugu otomatik hesaba katilir. Gecikme paylasilsin diye
     * tolerans uygulanir; sadece BEKLENENDEN BELIRGIN HIZLI vuruslar macro sayilir.
     */
    private boolean isMacroHit(Player player) {
        double speed = 4.0;
        var attr = player.getAttribute(Attribute.ATTACK_SPEED);
        if (attr != null && attr.getValue() > 0.01) speed = attr.getValue();

        long expectedMs = (long) (1000.0 / speed * attackTolerance);

        long now = System.currentTimeMillis();
        Long last = lastHit.put(player.getUniqueId(), now);
        if (last == null) return false;          // ilk vurus her zaman gecerli

        return (now - last) < expectedMs;
    }

    /**
     * Macro vurusunda yapilanlar: hasar zaten islenmez, burada sadece uyari verilir.
     * Geri savurma tamamen kaldirildi -- config'te eski bir deger kalsa bile
     * oyuncu artik hicbir sekilde itilmez.
     */
    private void punishMacro(Player player, Location center) {
        player.playSound(player.getLocation(), Sound.ENTITY_ZOMBIE_ATTACK_IRON_DOOR, 0.8f, 1.6f);
        player.sendActionBar(Component.text("Göstergenin dolmasını bekle!", NamedTextColor.RED));
    }

    // ==================== OVERLORD ====================

    private void spawnOverlord(Location loc) {
        World w = loc.getWorld();
        overlord = (Giant) w.spawnEntity(loc, EntityType.GIANT);
        overlord.addScoreboardTag(TAG);
        overlord.setPersistent(true);
        overlord.setRemoveWhenFarAway(false);
        overlord.setCustomNameVisible(true);

        var maxHp = overlord.getAttribute(Attribute.MAX_HEALTH);
        if (maxHp != null) {
            maxHp.setBaseValue(1024.0);
            overlord.setHealth(1024.0);
        }
        var atk = overlord.getAttribute(Attribute.ATTACK_DAMAGE);
        if (atk != null) atk.setBaseValue(meleeDamage);

        overlordState = new BossState("OVERLORD", overlordMaxHp, true);
        overlord.customName(overlordState.bar());

        abilityCounter = 0;
        resetting = false;
        homeReached = false;
        unseenSeconds = 0;
        diving = false;
        stormLock = false;
        clones.removeAll();
        lastMeleeTick.clear();

        if (customModel) model.spawn(overlord);

        sfx(loc, Sound.ENTITY_WITHER_SPAWN, 2.0f, 0.5f);
        sfx(loc, Sound.ENTITY_RAVAGER_ROAR, 3.0f, 0.35f);
        sfx(loc, Sound.ENTITY_WARDEN_EMERGE, 2.4f, 0.6f);
        Bukkit.broadcast(Component.text("Cursed Valley'de Overlord ortaya çıktı!", NamedTextColor.DARK_RED));
    }

    private void overlordDied() {
        clones.removeAll();
        Location loc = overlord.getLocation();
        World w = loc.getWorld();

        List<UUID> hitters = new ArrayList<>(overlordState.attackers().keySet());
        model.remove();
        overlord.remove();
        overlord = null;
        overlordState = null;

        sfx(loc, Sound.ENTITY_WITHER_DEATH, 2.0f, 0.6f);
        sfx(loc, Sound.ENTITY_RAVAGER_DEATH, 2.6f, 0.4f);
        sfx(loc, Sound.ENTITY_WARDEN_DEATH, 2.0f, 0.7f);
        giveDrops("overlord", hitters, loc, "Overlord");

        loc.getChunk().removePluginChunkTicket(this);

        // Bekleme boss OLDUGUNDE baslar.
        setNextSpawn(System.currentTimeMillis() + cooldownMillis);
        Bukkit.broadcast(Component.text("Overlord düştü. Vadi şimdilik sessiz.",
                NamedTextColor.GRAY));
    }

    // ==================== OVERLORD SESLERI ====================
    //
    // Giant vanilla'da tamamen sessizdir. Sesler burada elle uretiliyor:
    // her efekt birkac vanilla sesin pes perdeden ust uste binmesiyle
    // bossa ozel bir ton veriyor.

    private void sfx(Location at, Sound s, float vol, float pitch) {
        at.getWorld().playSound(at, s, org.bukkit.SoundCategory.HOSTILE, vol, pitch);
    }

    /** Bekleme kukremesi. */
    private void soundIdle(Location at) {
        sfx(at, Sound.ENTITY_RAVAGER_ROAR, 2.6f, 0.42f);
        sfx(at, Sound.ENTITY_WARDEN_HEARTBEAT, 2.2f, 0.55f);
        getServer().getScheduler().runTaskLater(this,
                () -> sfx(at, Sound.ENTITY_WITHER_AMBIENT, 1.6f, 0.45f), 6L);
    }

    /** Yakin dovus vurusu. */
    private void soundSwing(Location at) {
        sfx(at, Sound.ITEM_MACE_SMASH_GROUND, 1.6f, 0.55f);
        sfx(at, Sound.ENTITY_RAVAGER_ATTACK, 1.8f, 0.5f);
    }

    /** Hasar aldi. */
    private void soundHurt(Location at) {
        sfx(at, Sound.ENTITY_RAVAGER_HURT, 1.6f, 0.45f);
        sfx(at, Sound.BLOCK_ANVIL_LAND, 0.5f, 1.6f);
    }

    /**
     * Giant'in vanilla saldiri davranisi yoktur (uzerine yurur ama vurmaz).
     * Bu yuzden yakin dovus elle isleniyor.
     */
    private void meleeTick() {
        if (overlord == null || overlord.isDead()) return;
        if (casting || stormLock || diving) return;

        Location center = overlord.getLocation();
        long now = center.getWorld().getFullTime();

        for (Player p : center.getWorld().getPlayers()) {
            if (p.getGameMode() == GameMode.SPECTATOR || p.getGameMode() == GameMode.CREATIVE) continue;
            if (p.getLocation().distance(center) > meleeReach) continue;

            Long last = lastMeleeTick.get(p.getUniqueId());
            if (last != null && now - last < meleeCooldownTicks) continue;
            lastMeleeTick.put(p.getUniqueId(), now);

            hurt(p, meleeDamage);
            overlord.swingMainHand();
            if (customModel) model.playAttack();
            soundSwing(center);
        }
    }

    // ==================== FAZ YETENEKLERI ====================

    /**
     * Can barindaki turuncu esiklerden biri gecildiginde calisir.
     * %75 Gurz Firtinasi -- %50 Yeraltina Dalis -- %25 mini klonlar.
     */
    private void triggerPhase(int pct) {
        switch (pct) {
            case 75 -> gurzStorm();
            case 50 -> undergroundDive();
            case 25 -> summonClones();
            default -> { }
        }
    }

    /**
     * Gurz Firtinasi: Overlord yerinde doner, yakinindaki herkes hasar alip
     * disari savrulur. Uzaktakileri vurmaz -- dibine yiginmayi cezalandirir.
     */
    private void gurzStorm() {
        Location center = overlord.getLocation();
        World w = center.getWorld();

        Bukkit.broadcast(Component.text("Overlord gürzünü iki eline aldı!", NamedTextColor.GOLD));
        w.playSound(center, Sound.ENTITY_WARDEN_SONIC_CHARGE, SoundCategory.HOSTILE, 2.0f, 0.7f);

        final int raiseTicks = 25;           // kollari kaldirma suresi
        final int spinTicks = 5 * 14;        // 5 tur, tur basina 14 tick
        final int staggerTicks = 40;         // 2 saniye sersemleme

        // Firtina + sersemleme boyunca diger yetenekler ve melee kapali.
        stormLock = true;
        // Kaldirma suresi ile asagidaki gecikme AYNI olmali; yoksa model
        // vuruslardan once donmeye baslar.
        if (customModel) model.playStorm(raiseTicks, spinTicks);

        new org.bukkit.scheduler.BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                if (overlord == null || overlord.isDead()) {
                    stormLock = false;
                    cancel();
                    return;
                }

                if (t < spinTicks) {
                    Location c = overlord.getLocation();
                    World world = c.getWorld();

                    // Her turda bir kez vurur; tur ~14 tick.
                    if (t % 14 == 0) {
                        world.playSound(c, Sound.ENTITY_PLAYER_ATTACK_SWEEP,
                                SoundCategory.HOSTILE, 1.6f, 0.7f);
                        for (Player p : world.getPlayers()) {
                            if (p.getGameMode() == GameMode.SPECTATOR) continue;
                            if (p.getLocation().distance(c) > stormRadius) continue;

                            hurt(p, stormDamage);
                            Vector push = p.getLocation().toVector().subtract(c.toVector());
                            if (push.lengthSquared() < 0.01) push = new Vector(0, 0, 1);
                            push.setY(0).normalize().multiply(stormKnockback);
                            push.setY(0.55);
                            p.setVelocity(p.getVelocity().add(push));
                        }
                    }

                    // Donen gurzun izi
                    double a = Math.toRadians(t * 26.0);
                    world.spawnParticle(Particle.SWEEP_ATTACK, c.clone().add(
                            Math.cos(a) * stormRadius, 1.4, Math.sin(a) * stormRadius), 1, 0, 0, 0, 0);

                } else if (t == spinTicks) {
                    // Donme bitti: boss kendi hizindan sersemler.
                    if (customModel) model.playStagger(staggerTicks);
                    overlord.getWorld().playSound(overlord.getLocation(),
                            Sound.ENTITY_RAVAGER_ROAR, SoundCategory.HOSTILE, 1.6f, 0.9f);
                    Bukkit.broadcast(Component.text("Overlord sersemledi!", NamedTextColor.YELLOW));

                } else if (t >= spinTicks + staggerTicks) {
                    stormLock = false;
                    cancel();
                    return;
                }

                t++;
            }
        }.runTaskTimer(this, raiseTicks, 1L);
    }

    /**
     * Yeraltina Dalis: Overlord yere gomulur, birkac saniye kaybolur, sonra
     * rasgele bir oyuncunun altindan patlayarak cikar.
     *
     * Patlama HARITAYA ZARAR VERMEZ -- gercek bir explosion degil, elle
     * uygulanan hasar + itis + parcacik.
     */
    private void undergroundDive() {
        if (diving) return;
        diving = true;

        Location start = overlord.getLocation();
        World w = start.getWorld();

        Bukkit.broadcast(Component.text("Overlord yerin altına daldı!", NamedTextColor.DARK_RED));
        w.playSound(start, Sound.ENTITY_WARDEN_EMERGE, SoundCategory.HOSTILE, 2.0f, 0.6f);
        w.spawnParticle(Particle.BLOCK, start.clone().add(0, 0.2, 0), 120, 2.0, 0.3, 2.0, 0.15,
                w.getBlockAt(start.clone().subtract(0, 1, 0)).getBlockData());

        overlord.setInvisible(true);
        overlord.setAI(false);
        overlord.setInvulnerable(true);
        if (customModel) model.hide(true);

        // Guvenlik agi: herhangi bir sebeple cikis calismazsa boss sonsuza
        // kadar gorunmez ve dokunulmaz kalmasin.
        getServer().getScheduler().runTaskLater(this, () -> {
            if (diving) {
                diving = false;
                if (overlord != null && !overlord.isDead()) {
                    overlord.setInvisible(customModel);
                    overlord.setAI(true);
                    overlord.setInvulnerable(false);
                    if (customModel) model.hide(false);
                }
            }
        }, 160L);

        // Hedef, dalis bittiginde secilir; oyuncu kacarsa da altindan cikar.
        getServer().getScheduler().runTaskLater(this, () -> {
            if (overlord == null || overlord.isDead()) { diving = false; return; }

            // Yerde duran (ucmayan, yuzmeyen) oyuncular arasindan rasgele biri.
            List<Player> grounded = new ArrayList<>();
            for (Player p : start.getWorld().getPlayers()) {
                if (p.getGameMode() == GameMode.SPECTATOR) continue;
                if (!p.isOnGround()) continue;
                if (p.getLocation().distance(start) > abilityRadius * 2) continue;
                grounded.add(p);
            }
            Player target = grounded.isEmpty() ? pickTarget()
                    : grounded.get(ThreadLocalRandom.current().nextInt(grounded.size()));

            Location anchor = (target != null ? target.getLocation() : start).clone();
            // Tam ustunde degil, YANINDA cikar -- oyuncu sikismasin.
            double ang = ThreadLocalRandom.current().nextDouble() * Math.PI * 2;
            anchor.add(Math.cos(ang) * 3.0, 0, Math.sin(ang) * 3.0);
            // Hedefin kendi yuksekligine gore zemin ara -- yuzey degil.
            // Ic lambda bunu yakaladigi icin bir daha ATANMAMALI (effectively final).
            final Location out = Ground.findNear(anchor, anchor.getY());

            // Cikmadan once zemin titresir -- kacacak zaman kalsin.
            World world = out.getWorld();
            world.spawnParticle(Particle.BLOCK, out, 80, 1.4, 0.1, 1.4, 0.05,
                    world.getBlockAt(out.clone().subtract(0, 1, 0)).getBlockData());
            world.playSound(out, Sound.ENTITY_WARDEN_LISTENING_ANGRY, SoundCategory.HOSTILE, 1.6f, 0.8f);

            getServer().getScheduler().runTaskLater(this, () -> {
                if (overlord == null || overlord.isDead()) { diving = false; return; }

                overlord.teleport(out);
                overlord.setInvisible(customModel);   // ozel model varsa zaten gorunmez
                overlord.setAI(true);
                overlord.setInvulnerable(false);
                if (customModel) { model.hide(false); model.playSlamStrike(); }

                diveBurst(out);
                diving = false;
            }, 30L);
        }, 60L);
    }

    /** Cikis patlamasi: hasar + itis + parcacik. Blok kirilmaz, ates cikmaz. */
    private void diveBurst(Location at) {
        World w = at.getWorld();
        w.playSound(at, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.HOSTILE, 2.0f, 0.6f);
        w.spawnParticle(Particle.EXPLOSION_EMITTER, at.clone().add(0, 1, 0), 3, 1.0, 0.5, 1.0, 0);
        w.spawnParticle(Particle.BLOCK, at, 160, 2.5, 0.5, 2.5, 0.25,
                w.getBlockAt(at.clone().subtract(0, 1, 0)).getBlockData());

        for (Player p : w.getPlayers()) {
            if (p.getGameMode() == GameMode.SPECTATOR) continue;
            double d = p.getLocation().distance(at);
            if (d > diveRadius) continue;

            hurt(p, diveDamage * (1.0 - d / (diveRadius * 1.6)));
            Vector push = p.getLocation().toVector().subtract(at.toVector());
            if (push.lengthSquared() < 0.01) push = new Vector(0, 1, 0);
            push.setY(0).normalize().multiply(1.2);
            push.setY(0.9);
            p.setVelocity(p.getVelocity().add(push));
        }
    }

    /** %25: mini klonlar. */
    private void summonClones() {
        Bukkit.broadcast(Component.text("Overlord kendi suretlerini çağırdı!", NamedTextColor.DARK_RED));
        // Klonlar Overlord ile ayni yuvaya ve ayni tasma yaricapina bagli:
        // oyuncular onlari bowlun disina cekemez.
        World w = overlord.getWorld();
        clones.setLeash(new Location(w, crystalX + 0.5, crystalY, crystalZ + 0.5), leashRadius);
        clones.spawn(overlord.getLocation(), cloneCount, cloneHealth, (float) cloneScale, customModel);
    }

    /**
     * Yetenek: cember uyarisi -> Overlord havaya sicrar -> yere cakilir -> darbe.
     *
     * Sart: yaricap icinde VE gorus hattinda en az bir oyuncu olmali. Kimse
     * gorunmuyorsa yetenek hic baslamaz; basladiysa cemberden cikan kurtulur.
     */
    private void overlordAbility() {
        Location center = overlord.getLocation();
        World w = center.getWorld();

        boolean anySeen = false;
        for (Player p : w.getPlayers()) {
            if (p.getGameMode() == GameMode.SPECTATOR) continue;
            if (p.getLocation().distance(center) > abilityRadius) continue;
            if (!overlord.hasLineOfSight(p)) continue;
            anySeen = true;
            break;
        }
        if (!anySeen) return;

        drawCircle(w, center, abilityRadius);
        w.playSound(center, Sound.ENTITY_WARDEN_SONIC_CHARGE, 1.2f, 1.4f);

        // 1) Uyaridan sonra havaya sicra.
        getServer().getScheduler().runTaskLater(this, () -> {
            if (overlord == null || overlord.isDead()) return;

            // Yerçekimi 0.08/tick; h = v^2 / (2g) -> v = sqrt(2 * 0.08 * h)
            double v = Math.sqrt(2 * 0.08 * abilityJumpHeight);
            overlord.setVelocity(new Vector(0, v, 0));
            w.playSound(overlord.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 0.8f, 1.8f);

            if (customModel) model.playSlamRaise();

            int riseTicks = (int) Math.ceil(v / 0.08);   // tepe noktasina kadar
            slamAfterRise(riseTicks);
        }, abilityWarn * 20L);
    }

    /** Tepe noktasinda asagi firlatir, yere degince darbeyi uygular. */
    private void slamAfterRise(int riseTicks) {
        getServer().getScheduler().runTaskLater(this, () -> {
            if (overlord == null || overlord.isDead()) return;

            overlord.setVelocity(new Vector(0, -2.2, 0));   // bir anda asagi
            overlord.getWorld().playSound(overlord.getLocation(),
                    Sound.ENTITY_WARDEN_SONIC_BOOM, 1.4f, 1.2f);

            // Yere degdigi anda darbe; degmezse en gec 40 tick sonra.
            new org.bukkit.scheduler.BukkitRunnable() {
                int waited = 0;

                @Override
                public void run() {
                    if (overlord == null || overlord.isDead()) { cancel(); return; }
                    waited++;
                    if (overlord.isOnGround() || waited > 40) {
                        slamImpact();
                        cancel();
                    }
                }
            }.runTaskTimer(this, 2L, 1L);
        }, riseTicks);
    }

    /** Yere carpma darbesi: hasar + cemberin disina savurma. */
    private void slamImpact() {
        Location c2 = overlord.getLocation();
        World w = c2.getWorld();

        if (customModel) model.playSlamStrike();

        w.playSound(c2, Sound.ENTITY_GENERIC_EXPLODE, 1.4f, 0.6f);
        w.spawnParticle(Particle.EXPLOSION, c2.clone().add(0, 1, 0), 20, 4, 1, 4, 0.02);
        w.spawnParticle(Particle.BLOCK, c2, 120, 4, 0.4, 4, 0.1,
                w.getBlockAt(c2.clone().add(0, -1, 0)).getBlockData());

        for (Player p : w.getPlayers()) {
            if (p.getGameMode() == GameMode.SPECTATOR) continue;
            double dist = p.getLocation().distance(c2);
            if (dist > abilityRadius) continue;   // cemberden cikan kurtulur

            hurt(p, abilityDamage);
            launchOutside(p, c2, dist);
        }
    }

    /**
     * Oyuncuyu cemberin DISINA tasiyacak kadar firlatir.
     * Yatay hiz her tick azaldigi icin katedilen mesafe kabaca (hiz * 11) blok olur;
     * gereken mesafe buna gore hiza cevriliyor. Merkeze yakin olan daha sert savrulur.
     */
    private void launchOutside(Player player, Location center, double dist) {
        Vector away = player.getLocation().toVector().subtract(center.toVector());
        away.setY(0);
        if (away.lengthSquared() < 0.01) {
            away = new Vector(ThreadLocalRandom.current().nextDouble(-1, 1), 0,
                    ThreadLocalRandom.current().nextDouble(-1, 1));
            if (away.lengthSquared() < 0.01) away = new Vector(0, 0, 1);
        }
        away.normalize();

        double needed = (abilityRadius - dist) + 3.0;   // cemberi asmasi icin pay
        double speed = Math.max(0.45, Math.min(1.6, needed / 11.0 + 0.35)) * abilityKnockback;
        speed = Math.min(speed, 2.4);                  // ust sinir

        away.multiply(speed).setY(0.65);
        player.setVelocity(away);
    }

    /**
     * Yuvadan uzaklasma ve donus modu.
     *
     * Onceki surumdeki hata: mod acilir acilmaz "gorusunde oyuncu var mi" diye
     * bakiliyordu; dovusulen oyuncu zaten yakinda oldugu icin mod ayni saniyede
     * kapaniyor ve boss kovalamaya devam ediyordu.
     *
     * Dogrusu iki asamali: once EVE VARANA KADAR oyuncular tamamen yok sayilir,
     * eve vardiktan sonra gorus mesafesine oyuncu girene kadar beklenir.
     * Iki asamada da hizli can yenilenmesi surer.
     *
     * @return donus modundaysa true (takip ve yetenek yok)
     */
    private boolean checkLeash() {
        Location home = new Location(overlord.getWorld(),
                crystalX + 0.5, crystalY, crystalZ + 0.5);
        double dist = overlord.getLocation().distance(home);

        if (!resetting && dist > leashRadius) {
            resetting = true;
            homeReached = false;
            overlord.setTarget(null);
            overlord.getWorld().playSound(overlord.getLocation(),
                    Sound.ENTITY_WITHER_AMBIENT, 1.2f, 0.5f);
        }

        if (!resetting) return false;

        // --- 1. asama: eve don, oyuncu kim olursa olsun umursama ---
        if (!homeReached) {
            overlord.setTarget(null);

            if (dist <= 3.0) {
                homeReached = true;
                overlord.getWorld().spawnParticle(Particle.PORTAL,
                        home.clone().add(0, 2, 0), 40, 1.2, 1.5, 1.2, 0.3);
            } else if (dist > leashRadius * 1.5) {
                overlord.teleport(home);          // cok uzaktaysa yurumesini bekleme
                homeReached = true;
            } else {
                overlord.getPathfinder().moveTo(home, 1.4);
            }
            return true;
        }

        // --- 2. asama: evde bekle, oyuncu gelene kadar hizli yenilen ---
        Player seen = nearestPlayer(overlord.getLocation(), detectRadius);
        if (seen != null && overlord.hasLineOfSight(seen)) {
            resetting = false;
            overlord.setTarget(seen);
            return false;
        }
        return true;
    }

    /**
     * Meteor yagmuru.
     *
     * Overlord ellerini kaldirir, cevredeki rasgele noktalara 6-7 meteor cagirir.
     * Meteorlar patlamaz, blok kirmaz; sadece degdigi oyuncuya hasar verir.
     * Her meteor dusmeden once hedef noktada cember uyarisi belirir.
     */
    private void meteorAbility() {
        Location center = overlord.getLocation();
        World w = center.getWorld();

        boolean anySeen = false;
        for (Player p : w.getPlayers()) {
            if (p.getGameMode() == GameMode.SPECTATOR) continue;
            if (p.getLocation().distance(center) > meteorRadius) continue;
            if (!overlord.hasLineOfSight(p)) continue;
            anySeen = true;
            break;
        }
        if (!anySeen) return;

        // ---- Cagirma duruşu ----
        // Boss 1 saniye durur, gurzlu kolunu havaya kaldirir; meteorlar
        // ondan sonra yagar, kol iner ve dovuse devam eder.
        casting = true;
        overlord.setAI(false);
        overlord.setVelocity(new Vector(0, 0, 0));
        if (customModel) model.playMeteorCast(CAST_TICKS);

        sfx(center, Sound.ENTITY_ENDER_DRAGON_GROWL, 1.6f, 0.8f);
        sfx(center, Sound.ENTITY_WITHER_SHOOT, 1.8f, 0.5f);
        sfx(center, Sound.ENTITY_RAVAGER_ROAR, 2.4f, 0.4f);

        getServer().getScheduler().runTaskLater(this, () -> {
            casting = false;
            if (overlord == null || overlord.isDead()) return;
            overlord.setAI(true);

            Location c2 = overlord.getLocation();
            sfx(c2, Sound.ITEM_TRIDENT_THUNDER, 2.0f, 0.6f);

            int count = ThreadLocalRandom.current().nextInt(meteorMin, meteorMax + 1);
            for (int i = 0; i < count; i++) {
                Location spot = randomSpot(c2);
                // Meteorlar arka arkaya dussun, hepsi ayni anda degil
                getServer().getScheduler().runTaskLater(this, () -> markAndDrop(spot), i * 3L);
            }
        }, CAST_TICKS);
    }

    /** Yaricap icinde rasgele bir zemin noktasi secer. */
    private Location randomSpot(Location center) {
        var rnd = ThreadLocalRandom.current();
        double angle = rnd.nextDouble(Math.PI * 2);
        double r = meteorRadius * Math.sqrt(rnd.nextDouble());   // alana esit dagilim

        double x = center.getX() + r * Math.cos(angle);
        double z = center.getZ() + r * Math.sin(angle);

        Location probe = new Location(center.getWorld(), x, center.getY() + 3, z);
        // Zemini bul: en fazla 8 blok asagi bak
        for (int i = 0; i < 12; i++) {
            if (probe.getBlock().getType().isSolid()) {
                probe.add(0, 1, 0);
                break;
            }
            probe.add(0, -1, 0);
        }
        return probe;
    }

    /** Once uyari cemberi, sonra dusen meteor. */
    private void markAndDrop(Location target) {
        World w = target.getWorld();
        var dust = new Particle.DustOptions(Color.fromRGB(255, 120, 0), 1.6f);

        // Uyari cemberi: dusme anina kadar her 4 tick'te bir yenilenir
        new org.bukkit.scheduler.BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                if (t >= meteorWarnTicks) {
                    dropMeteor(target);
                    cancel();
                    return;
                }
                for (int i = 0; i < 24; i++) {
                    double a = (Math.PI * 2 / 24) * i;
                    w.spawnParticle(Particle.DUST, new Location(w,
                            target.getX() + meteorHitRadius * Math.cos(a),
                            target.getY() + 0.2,
                            target.getZ() + meteorHitRadius * Math.sin(a)),
                            1, 0, 0, 0, 0, dust);
                }
                t += 4;
            }
        }.runTaskTimer(this, 0L, 4L);
    }

    /**
     * Meteoru dusurur.
     *
     * Gercek bir varlik yerine asagi inen bir konum kullaniliyor: patlama yok,
     * blok hasari yok, sunucuya yuk yok. Degdigi oyuncuya sabit hasar verir.
     */
    private void dropMeteor(Location target) {
        World w = target.getWorld();
        Location head = target.clone().add(0, 26, 0);

        w.playSound(target, Sound.ENTITY_BLAZE_SHOOT, 1.2f, 0.7f);

        new org.bukkit.scheduler.BukkitRunnable() {
            @Override
            public void run() {
                head.add(0, -2.6, 0);   // hizli insin

                w.spawnParticle(Particle.FLAME, head, 12, 0.25, 0.25, 0.25, 0.01);
                w.spawnParticle(Particle.LARGE_SMOKE, head, 4, 0.2, 0.2, 0.2, 0.01);

                // Yolda bir oyuncuya degerse hemen patlasin
                // Hizli indigi icin tek nokta yerine gectigi yol boyunca bakiyoruz;
                // aksi halde meteor oyuncunun icinden atlayip gecebiliyordu.
                for (Player p : w.getPlayers()) {
                    if (p.getGameMode() == GameMode.SPECTATOR) continue;
                    Location body = p.getLocation().clone().add(0, 1.0, 0);
                    double dy = Math.abs(body.getY() - head.getY());
                    double dxz = Math.hypot(body.getX() - head.getX(), body.getZ() - head.getZ());
                    if (dxz <= meteorHitRadius && dy <= 2.8) {
                        impact(body);
                        cancel();
                        return;
                    }
                }

                if (head.getY() <= target.getY() || head.getBlock().getType().isSolid()) {
                    impact(target);
                    cancel();
                }
            }

            private void impact(Location at) {
                w.playSound(at, Sound.ENTITY_GENERIC_EXPLODE, 0.9f, 1.5f);
                w.spawnParticle(Particle.FLAME, at, 40, 1.0, 0.5, 1.0, 0.08);
                w.spawnParticle(Particle.LAVA, at, 8, 0.6, 0.3, 0.6, 0);

                for (Player p : w.getPlayers()) {
                    if (p.getGameMode() == GameMode.SPECTATOR) continue;
                    if (p.getLocation().distance(at) > meteorHitRadius) continue;
                    hurt(p, meteorDamage);
                    p.setFireTicks(Math.max(p.getFireTicks(), meteorFireTicks));
                }
            }
        }.runTaskTimer(this, 0L, 1L);
    }

    private void drawCircle(World w, Location center, double radius) {
        var dust = new Particle.DustOptions(Color.fromRGB(255, 40, 40), 2.0f);
        for (int i = 0; i < 90; i++) {
            double a = (Math.PI * 2 / 90) * i;
            double x = center.getX() + radius * Math.cos(a);
            double z = center.getZ() + radius * Math.sin(a);
            w.spawnParticle(Particle.DUST, new Location(w, x, center.getY() + 0.4, z),
                    2, 0.05, 0.4, 0.05, 0, dust);
        }
    }

    // ==================== SANIYELIK DONGU ====================

    private void everySecond() {
        tickCounter++;

        dragon.everySecond(dragonRegen, dragonRegenSeconds);

        // her ~7 saniyede bir kukreme
        if (overlord != null && !overlord.isDead() && tickCounter % 7 == 0) {
            soundIdle(overlord.getLocation());
        }

        if (tickCounter % regenSeconds == 0
                && crystalState != null && crystal != null && !crystal.isDead()) {
            crystalState.heal(crystalRegen);
        }

        // Overlord donus modundayken hizli, savasirken normal yenilenir.
        if (overlordState != null && overlord != null && !overlord.isDead()) {
            if (resetting) {
                if (tickCounter % resetRegenSeconds == 0) overlordState.heal(resetRegen);
            } else {
                if (tickCounter % regenSeconds == 0) overlordState.heal(overlordRegen);
            }
        }

        if (crystalState != null && crystalBar != null && !crystalBar.isDead()) {
            crystalBar.customName(crystalState.bar());
        }

        if (overlordState != null && overlord != null && !overlord.isDead()) {
            overlord.customName(overlordState.bar());

            // Yuvasindan cok uzaklastiysa geri doner (oyuncular onu vadiden kacirmasin).
            if (!checkLeash()) {
                Player target = pickTarget();
                if (target != null) {
                    overlord.setTarget(target);
                    overlord.getPathfinder().moveTo(target, 1.0);
                } else {
                    overlord.setTarget(null);
                }
            }

            // Kimse gormuyorsa boss kendini toparlar: oyuncular onu yipratip
            // birakip gitmesin diye 30 saniye sonra cani tamamen dolar.
            boolean seen = false;
            for (Player p : overlord.getWorld().getPlayers()) {
                if (p.getGameMode() == GameMode.SPECTATOR) continue;
                if (p.getLocation().distance(overlord.getLocation()) > abilityRadius * 2) continue;
                if (!overlord.hasLineOfSight(p)) continue;
                seen = true;
                break;
            }
            if (seen) {
                unseenSeconds = 0;
            } else if (++unseenSeconds >= unseenResetSeconds) {
                unseenSeconds = 0;
                if (overlordState.health() < overlordState.maxHealth()) {
                    overlordState.healFull();
                    clones.removeAll();
                    overlord.customName(overlordState.bar());
                    Bukkit.broadcast(Component.text(
                            "Overlord yalnız kaldı ve tamamen iyileşti.", NamedTextColor.DARK_RED));
                }
            }

            abilityCounter++;
            if (!resetting && !diving && !stormLock && abilityCounter >= abilityInterval) {
                abilityCounter = 0;
                // Her turda yazi tura: ya yere cakilma ya meteor yagmuru.
                if (ThreadLocalRandom.current().nextBoolean()) overlordAbility();
                else meteorAbility();
            }
        }

        boolean idle = (crystal == null || crystal.isDead())
                && (overlord == null || overlord.isDead());
        if (idle && System.currentTimeMillis() >= nextSpawnAt) {
            spawnCrystal();
        }
    }

    /**
     * Bosun oyuncuya verdigi hasar.
     *
     * Normal hasar zirhla azalir: netherite zirhla 18 hasar oyuncuya 4-5 olarak
     * ulasiyordu. MAGIC turu zirhi delip gecer, yani config'te yazan sayi
     * dogrudan cana isler. Direnc iksiri ve Koruma buyusu yine etkilidir.
     */
    private void hurt(Player player, double amount) {
        DamageSource source = DamageSource.builder(DamageType.MAGIC)
                .withCausingEntity(overlord)
                .withDirectEntity(overlord)
                .build();
        player.damage(amount, source);
        damageArmor(player, amount);
    }

    /**
     * Hedef secimi.
     *
     * Cogu zaman en yakin oyuncuyu kovalar; ama arada bir menzildeki baska bir
     * oyuncuya doner. Boylece hep ayni kisiye kilitlenip kalmaz, arkadan vuran
     * oyuncular da risk almis olur.
     */
    private Player pickTarget() {
        List<Player> nearby = new ArrayList<>();
        Location loc = overlord.getLocation();
        for (Player p : loc.getWorld().getPlayers()) {
            if (p.getGameMode() == GameMode.SPECTATOR || p.getGameMode() == GameMode.CREATIVE) continue;
            if (p.getLocation().distance(loc) <= detectRadius) nearby.add(p);
        }
        if (nearby.isEmpty()) return null;

        targetSwitch++;
        // Yaklasik 8 saniyede bir rasgele birine doner
        if (nearby.size() > 1 && targetSwitch >= targetSwitchSeconds) {
            targetSwitch = 0;
            return nearby.get(ThreadLocalRandom.current().nextInt(nearby.size()));
        }
        return nearestPlayer(loc, detectRadius);
    }

    /**
     * Zirh yipratma.
     *
     * MAGIC hasari zirhi delip gectigi icin oyun zirha dayaniklilik kaybi
     * uygulamiyor. Vanilla mantigini elle isletiyoruz: gelen hasarin dortte biri
     * kadar (en az 1) yipranma, Dayaniklilik buyusu ihtimali de hesaba katilarak.
     */
    private void damageArmor(Player player, double amount) {
        int wear = Math.max(1, (int) Math.round(amount / 4.0));
        var inv = player.getInventory();
        ItemStack[] armor = inv.getArmorContents();
        boolean changed = false;

        for (int i = 0; i < armor.length; i++) {
            ItemStack piece = armor[i];
            if (piece == null || piece.getType().isAir()) continue;
            if (!(piece.getItemMeta() instanceof org.bukkit.inventory.meta.Damageable dmg)) continue;

            int applied = 0;
            for (int n = 0; n < wear; n++) {
                int unb = piece.getEnchantmentLevel(org.bukkit.enchantments.Enchantment.UNBREAKING);
                // Zirhta Dayaniklilik: her puan icin (60 + 40/(seviye+1)) / 100 ihtimalle yipranir
                if (unb > 0 && ThreadLocalRandom.current().nextDouble()
                        > (0.6 + 0.4 / (unb + 1))) continue;
                applied++;
            }
            if (applied == 0) continue;

            int max = piece.getType().getMaxDurability();
            int now = dmg.getDamage() + applied;
            if (now >= max) {
                armor[i] = null;                       // parca kirildi
                player.playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, 1.0f, 1.0f);
            } else {
                dmg.setDamage(now);
                piece.setItemMeta(dmg);
                armor[i] = piece;
            }
            changed = true;
        }
        if (changed) inv.setArmorContents(armor);
    }

    private Player nearestPlayer(Location loc, double max) {
        Player best = null;
        double bestDist = max * max;
        for (Player p : loc.getWorld().getPlayers()) {
            if (p.getGameMode() == GameMode.SPECTATOR || p.getGameMode() == GameMode.CREATIVE) continue;
            double d = p.getLocation().distanceSquared(loc);
            if (d < bestDist) { bestDist = d; best = p; }
        }
        return best;
    }

    // ==================== HASAR ====================

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDamage(EntityDamageByEntityEvent event) {
        Entity victim = event.getEntity();

        Player attacker = null;
        boolean melee = false;
        if (event.getDamager() instanceof Player p) {
            attacker = p;
            melee = true;
        } else if (event.getDamager() instanceof Projectile pr
                && pr.getShooter() instanceof Player p2) {
            attacker = p2;
        }

        // --- Macro korumasi: Cursed Valley'deki TUM yaratik ve bosslar icin ---
        // Oyuncular arasi vurusa karismaz.
        //
        // DIKKAT: sadece LivingEntity kontrolu YETMEZ. End Crystal canli bir varlik
        // degildir (LivingEntity'den turemez), o yuzden kristal kapsam disinda kalirdi.
        // Bu nedenle bizim bosslarimiz etikete gore ayrica dahil ediliyor.
        boolean isBossEntity = victim.getScoreboardTags().contains(TAG);
        boolean isCreature = victim instanceof LivingEntity && !(victim instanceof Player);
        boolean inValley = victim.getWorld().getName().equalsIgnoreCase(worldName);

        // Kontrol TEK SEFER calisir (icinde zaman damgasi guncelledigi icin
        // iki kez cagrilirsa olcum bozulur), sonucu asagida da kullaniliyor.
        boolean macro = attacker != null && melee
                && (isCreature || isBossEntity) && inValley
                && isMacroHit(attacker);

        if (macro) {
            event.setCancelled(true);   // HASAR YOK
            punishMacro(attacker, victim.getLocation());
            return;
        }

        if (crystal != null && BossState.same(victim, crystal)) {
            event.setCancelled(true);   // vanilla patlamayi engelle, cani biz tutuyoruz
            if (attacker == null || macro) return;   // macro vurusu cana islemez

            double dmg = event.getFinalDamage() > 0 ? event.getFinalDamage() : event.getDamage();
            crystal.getWorld().spawnParticle(Particle.CRIT,
                    crystal.getLocation().add(0, 1, 0), 6, 0.4, 0.4, 0.4, 0.05);

            if (crystalState.hit(attacker, dmg)) crystalDied();
            else if (crystalBar != null) crystalBar.customName(crystalState.bar());
            return;
        }

        // --- Lizard Dragon: cani elle tutuluyor ---
        if (dragon.is(victim)) {
            event.setCancelled(true);
            if (attacker == null || macro) return;
            double dmg = event.getFinalDamage() > 0 ? event.getFinalDamage() : event.getDamage();
            List<UUID> hitters = dragon.hit(attacker, dmg);
            if (hitters != null) {
                giveDrops("dragon", hitters, victim.getLocation(), "Lizard Dragon");
            }
            return;
        }

        // --- mini klonlar: canlari elle tutuluyor ---
        if (clones.isClone(victim)) {
            event.setCancelled(true);
            if (attacker == null || macro) return;
            double dmg = event.getFinalDamage() > 0 ? event.getFinalDamage() : event.getDamage();
            List<UUID> hitters = clones.hit(victim, attacker, dmg);
            if (hitters != null) {
                // Klon oldu -- kendi drop tablosundan odul verir.
                giveDrops("overlord_clone", hitters, victim.getLocation(), "Mini Overlord");
            }
            return;
        }

        if (overlord != null && BossState.same(victim, overlord)) {
            event.setCancelled(true);
            if (attacker == null || macro) return;   // macro vurusu cana islemez
            double dmg = event.getFinalDamage() > 0 ? event.getFinalDamage() : event.getDamage();
            if (overlordState.hit(attacker, dmg)) {
                overlordDied();
            } else {
                overlord.customName(overlordState.bar());
                int phase = overlordState.takePhase();
                if (phase > 0) triggerPhase(phase);
                overlord.playHurtAnimation(0f);
                if (--hurtSoundCooldown <= 0) {
                    hurtSoundCooldown = 5;          // her vurusta bagirmasin
                    soundHurt(overlord.getLocation());
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAnyDamage(EntityDamageEvent event) {
        Entity e = event.getEntity();
        boolean isBoss = (crystal != null && BossState.same(e, crystal))
                || (overlord != null && BossState.same(e, overlord));
        if (isBoss && !(event instanceof EntityDamageByEntityEvent)) {
            event.setCancelled(true);
        }
    }

    /**
     * Ejder haritayi yiyip bitirmesin.
     *
     * Ender Dragon iki ayri yoldan blok kirar: govdesinin degdigi bloklari
     * dogrudan siler (EntityExplodeEvent degil, bu olay) ve ates topu birakir.
     * Ikisi de burada kapatiliyor -- Cursed Valley'de tek bir blok bile
     * kirilmamali.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDragonBreakBlock(org.bukkit.event.entity.EntityChangeBlockEvent event) {
        if (event.getEntity().getScoreboardTags().contains(LizardDragon.TAG)) {
            event.setCancelled(true);
        }
    }

    /** Ejderin nefesi ve ates toplari zemini tutusturmasin. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDragonFireball(org.bukkit.event.entity.EntitySpawnEvent event) {
        if (!dragon.alive()) return;
        if (!event.getEntity().getWorld().getName().equalsIgnoreCase(worldName)) return;
        var type = event.getEntity().getType();
        if (type == org.bukkit.entity.EntityType.DRAGON_FIREBALL
                || type == org.bukkit.entity.EntityType.AREA_EFFECT_CLOUD) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onExplode(EntityExplodeEvent event) {
        if (event.getEntity().getScoreboardTags().contains(TAG)
                || event.getEntity().getScoreboardTags().contains(LizardDragon.TAG)) {
            event.blockList().clear();
        }
    }

    /**
     * Cursed Valley'de olen HER yaratik icin sandik.
     *
     * Yaratigin normal droplari (vanilla + varsa /cvmobs kayitlari) yere
     * dokulmez, oldurenin onundeki kutuya girer. Tecrube normal akar.
     * Bosslar buraya girmez -- onlarin kendi olum akisi var.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMobDeath(EntityDeathEvent event) {
        if (!mobChestEnabled) return;

        LivingEntity dead = event.getEntity();
        if (dead instanceof Player) return;
        if (dead.getScoreboardTags().contains(TAG)) return;          // boss / boss parcasi
        if (clones.isClone(dead)) return;                            // klonun kendi akisi var
        if (dead.getScoreboardTags().contains(LizardDragon.TAG)) return;  // ejderin kendi akisi var
        if (overlord != null && BossState.same(dead, overlord)) return;
        if (!dead.getWorld().getName().equalsIgnoreCase(worldName)) return;

        Player killer = dead.getKiller();
        if (killer == null || !killer.isOnline()) return;

        List<ItemStack> loot = new ArrayList<>();
        for (ItemStack it : event.getDrops()) {
            if (it != null && !it.getType().isAir()) loot.add(it.clone());
        }
        // Bu yaratik icin ayrica kayitli drop varsa onlar da kutuya girer.
        loot.addAll(drops.roll(dead.getType().name()));
        if (loot.isEmpty()) return;

        Location at = dead.getLocation().clone().add(0, 1, 0);
        if (lootChest.drop(at, killer, loot, mobChestStyle)) {
            event.getDrops().clear();   // yere hicbir sey dokulmesin
        }
    }

    // ==================== DROP ====================

    private void giveDrops(String mob, List<UUID> hitters, Location loc, String bossName) {
        if (hitters.isEmpty()) return;

        // Vuranlardan RASGELE biri secilir.
        UUID chosenId = hitters.get(ThreadLocalRandom.current().nextInt(hitters.size()));
        Player chosen = Bukkit.getPlayer(chosenId);
        String who = chosen != null ? chosen.getName() : Bukkit.getOfflinePlayer(chosenId).getName();
        if (who == null) who = "?";

        List<ItemStack> won = drops.roll(mob);
        if (won.isEmpty()) {
            Bukkit.broadcast(Component.text(bossName + " bu sefer hiçbir şey bırakmadı.",
                    NamedTextColor.GRAY));
            return;
        }

        // ---- Ganimet sandigi ----
        // Sadece cursedvalley dunyasindaki BOSSLAR icin. Siradan yaratiklar
        // eskisi gibi yere esya dokmeye devam eder (onlara ayri sandik yapilacak).
        boolean isBoss = mob.equalsIgnoreCase("crystal") || mob.equalsIgnoreCase("overlord");
        boolean inValley = loc.getWorld() != null
                && loc.getWorld().getName().equalsIgnoreCase(worldName);

        if (lootChestEnabled && isBoss && inValley
                && lootChest.drop(loc.clone().add(0, 1, 0), chosen, won, bossChestStyle)) {
            Bukkit.broadcast(Component.text(bossName + " > ", NamedTextColor.DARK_RED)
                    .append(Component.text(who, NamedTextColor.YELLOW))
                    .append(Component.text(" ganimet sandığını kazandı!", NamedTextColor.WHITE)));
            for (ItemStack reward : won) {
                Bukkit.broadcast(Component.text("   • ", NamedTextColor.DARK_GRAY)
                        .append(itemName(reward).color(NamedTextColor.AQUA))
                        .append(Component.text(" x" + reward.getAmount(), NamedTextColor.AQUA)));
            }
            return;
        }

        // Sandik kurulamadi (oyuncu cevrimdisi, kapali, ya da boss disi) -> eski yol.
        Location dropAt = (chosen != null && chosen.isOnline())
                ? chosen.getLocation().clone().add(0, 1.2, 0)
                : loc.clone().add(0, 1, 0);

        for (ItemStack reward : won) {
            Item dropped = dropAt.getWorld().dropItem(dropAt, reward);
            dropped.setOwner(chosenId);
            dropped.setVelocity(new Vector(0, 0.1, 0));   // ayaklarina dussun, savrulmasin

            Bukkit.broadcast(Component.text(bossName + " > ", NamedTextColor.DARK_RED)
                    .append(Component.text(who, NamedTextColor.YELLOW))
                    .append(Component.text(" ödülü kazandı: ", NamedTextColor.WHITE))
                    .append(itemName(reward).color(NamedTextColor.AQUA))
                    .append(Component.text(" x" + reward.getAmount(), NamedTextColor.AQUA)));
        }
    }

    /**
     * Esyanin GORUNEN adini verir.
     *
     * Ozel esyalar adini iki ayri bilesende tasiyabilir:
     *   - custom_name  (yeniden adlandirma, orsta verilen ad)  -> displayName()
     *   - item_name    (esyanin kendi adi, datapack'te tanimli) -> itemName()
     * Ruby esyalari item_name kullaniyor; sadece displayName'e bakildigi icin
     * "Ruby Balta" yerine "Netherite Balta" yaziliyordu. Ikisi de yoksa tur adi.
     */
    private Component itemName(ItemStack stack) {
        var meta = stack.getItemMeta();
        if (meta != null) {
            if (meta.hasDisplayName()) {
                Component c = meta.displayName();
                if (c != null) return c;
            }
            if (meta.hasItemName()) {
                Component c = meta.itemName();
                if (c != null) return c;
            }
        }
        return Component.translatable(stack.getType().translationKey());
    }

    // ==================== KOMUTLAR ====================

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            help(sender);
            return true;
        }

        switch (args[0].toLowerCase()) {

            case "reload" -> {
                loadSettings();
                sender.sendMessage(Component.text("Ayarlar yeniden yüklendi.", NamedTextColor.GREEN));
            }

            case "dragon", "ejder" -> {
                World w = world();
                if (w == null) {
                    sender.sendMessage(Component.text("Dünya bulunamadı: " + worldName,
                            NamedTextColor.RED));
                    return true;
                }
                try {
                    if (args.length > 1 && args[1].equalsIgnoreCase("remove")) {
                        dragon.remove();
                        sender.sendMessage(Component.text("Lizard Dragon kaldırıldı.",
                                NamedTextColor.GREEN));
                    } else {
                        dragon.spawn(w);
                        sender.sendMessage(Component.text("Lizard Dragon çağrıldı.",
                                NamedTextColor.GREEN));
                    }
                } catch (Exception ex) {
                    // "An unexpected error occurred" yerine gercek sebebi goster.
                    sender.sendMessage(Component.text("Ejder çağrılamadı: " + ex,
                            NamedTextColor.RED));
                    getLogger().severe("Ejder çağrılamadı:");
                    ex.printStackTrace();
                }
            }

            case "spawn" -> {
                removeCrystal();
                setNextSpawn(0L);
                spawnCrystal();
                sender.sendMessage(Component.text("Kristal çağrıldı.", NamedTextColor.GREEN));
            }

            case "info" -> {
                sender.sendMessage(Component.text("--- Etkin ayarlar ---", NamedTextColor.LIGHT_PURPLE));
                sender.sendMessage(Component.text("config sürümü: "
                        + getConfig().getInt("config-version", 0) + " (kod: " + CONFIG_VERSION + ")",
                        NamedTextColor.GRAY));
                sender.sendMessage(Component.text("meteor: " + meteorMin + "-" + meteorMax
                        + " adet, r=" + meteorRadius + ", hasar " + meteorDamage, NamedTextColor.GRAY));
                sender.sendMessage(Component.text("yakın dövüş: " + meleeDamage
                        + " | yetenek: " + abilityDamage, NamedTextColor.GRAY));
                sender.sendMessage(Component.text("dönüş: " + leashRadius
                        + " blok | görüş: " + detectRadius + " blok", NamedTextColor.GRAY));
            }

            case "drop", "drops" -> handleDrop(sender, args);

            default -> help(sender);
        }
        return true;
    }

    private void handleDrop(CommandSender sender, String[] args) {
        if (args.length < 3) {
            help(sender);
            return;
        }
        String action = args[1].toLowerCase();
        String mob = DropRegistry.normalize(args[2]);

        switch (action) {

            // /cvmobs drop add <yaratik> <oran>   -> elindeki esyayi ekler
            case "add" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(Component.text("Bu komutu oyuncu olarak kullan.", NamedTextColor.RED));
                    return;
                }
                if (args.length < 4) {
                    sender.sendMessage(Component.text("Kullanım: /cvmobs drop add <yaratık> <oran 0-100>",
                            NamedTextColor.RED));
                    return;
                }
                ItemStack inHand = player.getInventory().getItemInMainHand();
                if (inHand.getType().isAir()) {
                    sender.sendMessage(Component.text("Elinde eşya yok.", NamedTextColor.RED));
                    return;
                }
                double pct;
                try {
                    pct = Double.parseDouble(args[3].replace(',', '.').replace("%", ""));
                } catch (NumberFormatException e) {
                    sender.sendMessage(Component.text("Oran sayı olmalı: 0-100", NamedTextColor.RED));
                    return;
                }
                drops.add(mob, inHand, pct / 100.0);
                drops.save(getConfig());
                saveConfig();

                sender.sendMessage(Component.text("Eklendi > ", NamedTextColor.GREEN)
                        .append(itemName(inHand).color(NamedTextColor.AQUA))
                        .append(Component.text(" x" + inHand.getAmount(), NamedTextColor.AQUA))
                        .append(Component.text("  |  " + mob + "  |  %" + trim(pct), NamedTextColor.WHITE)));
            }

            // /cvmobs drop list <yaratik>
            case "list" -> {
                List<DropRegistry.Entry> list = drops.entries(mob);
                if (list.isEmpty()) {
                    sender.sendMessage(Component.text(mob + " için kayıtlı drop yok.", NamedTextColor.GRAY));
                    return;
                }
                sender.sendMessage(Component.text("--- " + mob + " drop listesi ---",
                        NamedTextColor.LIGHT_PURPLE));
                int i = 1;
                for (DropRegistry.Entry e : list) {
                    sender.sendMessage(Component.text(i++ + ". ", NamedTextColor.GRAY)
                            .append(itemName(e.item()).color(NamedTextColor.AQUA))
                            .append(Component.text(" x" + e.item().getAmount(), NamedTextColor.AQUA))
                            .append(Component.text("  -  %" + trim(e.chance() * 100),
                                    NamedTextColor.YELLOW)));
                }
            }

            // /cvmobs drop remove <yaratik> <sira>
            case "remove" -> {
                if (args.length < 4) {
                    sender.sendMessage(Component.text("Kullanım: /cvmobs drop remove <yaratık> <sıra>",
                            NamedTextColor.RED));
                    return;
                }
                int index;
                try {
                    index = Integer.parseInt(args[3]);
                } catch (NumberFormatException e) {
                    sender.sendMessage(Component.text("Sıra numarası gir (/cvmobs drop list ile gör).",
                            NamedTextColor.RED));
                    return;
                }
                if (drops.remove(mob, index)) {
                    drops.save(getConfig());
                    saveConfig();
                    sender.sendMessage(Component.text(index + ". kayıt silindi.", NamedTextColor.GREEN));
                } else {
                    sender.sendMessage(Component.text("O sırada kayıt yok.", NamedTextColor.RED));
                }
            }

            case "clear" -> {
                drops.clear(mob);
                drops.save(getConfig());
                saveConfig();
                sender.sendMessage(Component.text(mob + " drop listesi boşaltıldı.", NamedTextColor.GREEN));
            }

            default -> help(sender);
        }
    }

    private static String trim(double v) {
        if (Math.abs(v - Math.rint(v)) < 0.001) return String.valueOf((long) Math.rint(v));
        return String.valueOf(Math.round(v * 100.0) / 100.0);
    }

    private void help(CommandSender sender) {
        sender.sendMessage(Component.text("--- CursedValleyMobsAndMob ---", NamedTextColor.LIGHT_PURPLE));
        sender.sendMessage(Component.text("/cvmobs drop add <yaratık> <oran>   (elindeki eşya)", NamedTextColor.GRAY));
        sender.sendMessage(Component.text("/cvmobs drop list <yaratık>", NamedTextColor.GRAY));
        sender.sendMessage(Component.text("/cvmobs drop remove <yaratık> <sıra>", NamedTextColor.GRAY));
        sender.sendMessage(Component.text("/cvmobs drop clear <yaratık>", NamedTextColor.GRAY));
        sender.sendMessage(Component.text("/cvmobs spawn   |   /cvmobs reload   |   /cvmobs info", NamedTextColor.GRAY));
        sender.sendMessage(Component.text("/cvmobs dragon   |   /cvmobs dragon remove", NamedTextColor.GRAY));
        sender.sendMessage(Component.text("Yaratık adları: crystal, overlord, overlord_clone, dragon",
                NamedTextColor.DARK_GRAY));
        sender.sendMessage(Component.text("sürüm: " + getPluginMeta().getVersion(),
                NamedTextColor.DARK_GRAY));
    }
}
