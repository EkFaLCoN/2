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
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
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

    private double attackTolerance, macroKnockback;
    private double meleeDamage, meleeReach;
    private int meleeCooldownTicks;

    private int abilityInterval, abilityWarn;
    private double abilityRadius, abilityDamage, abilityKnockback, abilityJumpHeight;

    private final DropRegistry drops = new DropRegistry();

    // --- canli durum ---
    private EnderCrystal crystal;
    private BossState crystalState;
    private Entity crystalBar;

    private Giant overlord;
    private BossState overlordState;

    private long nextSpawnAt;                 // epoch ms
    private int tickCounter, abilityCounter;

    /** Yakin dovus icin oyuncu basina son vurus zamani (dunya tick'i). */
    private final Map<UUID, Long> lastMeleeTick = new HashMap<>();

    /** Macro tespiti: oyuncu basina son gecerli vurus zamani (ms). */
    private final Map<UUID, Long> lastHit = new HashMap<>();

    private static final String TAG = "cv_boss";

    // ==================== ACILIS ====================

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();
        getServer().getPluginManager().registerEvents(this, this);
        cleanupLeftovers();

        getServer().getScheduler().runTaskTimer(this, this::everySecond, 20L, 20L);
        getServer().getScheduler().runTaskTimer(this, this::meleeTick, 10L, 10L);

        getLogger().info("CursedValleyMobsAndMob etkin — dünya: " + worldName
                + " | kristal: " + crystalX + "/" + crystalY + "/" + crystalZ
                + " | bekleme: " + (cooldownMillis / 3600000.0) + " saat");
    }

    @Override
    public void onDisable() {
        removeCrystal();
        if (overlord != null && !overlord.isDead()) overlord.remove();
    }

    private void loadSettings() {
        reloadConfig();
        var c = getConfig();
        worldName      = c.getString("world", "cursedvalley");
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
        macroKnockback  = c.getDouble("macro-knockback", 0.95);

        meleeDamage        = c.getDouble("overlord.melee-damage", 7);
        meleeReach         = c.getDouble("overlord.melee-reach", 4.0);
        meleeCooldownTicks = Math.max(5, c.getInt("overlord.melee-cooldown-ticks", 20));

        abilityInterval = Math.max(1, c.getInt("overlord.ability.interval-seconds", 10));
        abilityWarn     = Math.max(1, c.getInt("overlord.ability.warn-seconds", 1));
        abilityRadius   = c.getDouble("overlord.ability.radius", 10);
        abilityDamage   = c.getDouble("overlord.ability.damage", 18);
        abilityKnockback = c.getDouble("overlord.ability.knockback-multiplier", 2.3);

        abilityJumpHeight = c.getDouble("overlord.ability.jump-height", 10.0);

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

    private void punishMacro(Player player, Location center) {
        Vector away = player.getLocation().toVector().subtract(center.toVector());
        away.setY(0);
        if (away.lengthSquared() < 0.01) away = new Vector(0, 0, 1);
        away.normalize().multiply(macroKnockback).setY(0.45);
        player.setVelocity(away);

        player.playSound(player.getLocation(), Sound.ENTITY_ZOMBIE_ATTACK_IRON_DOOR, 0.8f, 1.6f);
        player.sendActionBar(Component.text(
                "Macro vuruşları kabul edilmiyor — göstergenin dolmasını bekle!",
                NamedTextColor.RED));
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

        overlordState = new BossState("OVERLORD", overlordMaxHp);
        overlord.customName(overlordState.bar());

        abilityCounter = 0;
        lastMeleeTick.clear();

        w.playSound(loc, Sound.ENTITY_WITHER_SPAWN, 1.0f, 0.6f);
        Bukkit.broadcast(Component.text("Cursed Valley'de Overlord ortaya çıktı!", NamedTextColor.DARK_RED));
    }

    private void overlordDied() {
        Location loc = overlord.getLocation();
        World w = loc.getWorld();

        List<UUID> hitters = new ArrayList<>(overlordState.attackers().keySet());
        overlord.remove();
        overlord = null;
        overlordState = null;

        w.playSound(loc, Sound.ENTITY_WITHER_DEATH, 1.0f, 0.8f);
        giveDrops("overlord", hitters, loc, "Overlord");

        loc.getChunk().removePluginChunkTicket(this);

        // Bekleme boss OLDUGUNDE baslar.
        setNextSpawn(System.currentTimeMillis() + cooldownMillis);
        Bukkit.broadcast(Component.text("Overlord düştü. Vadi şimdilik sessiz.",
                NamedTextColor.GRAY));
    }

    /**
     * Giant'in vanilla saldiri davranisi yoktur (uzerine yurur ama vurmaz).
     * Bu yuzden yakin dovus elle isleniyor.
     */
    private void meleeTick() {
        if (overlord == null || overlord.isDead()) return;

        Location center = overlord.getLocation();
        long now = center.getWorld().getFullTime();

        for (Player p : center.getWorld().getPlayers()) {
            if (p.getGameMode() == GameMode.SPECTATOR || p.getGameMode() == GameMode.CREATIVE) continue;
            if (p.getLocation().distance(center) > meleeReach) continue;

            Long last = lastMeleeTick.get(p.getUniqueId());
            if (last != null && now - last < meleeCooldownTicks) continue;
            lastMeleeTick.put(p.getUniqueId(), now);

            p.damage(meleeDamage, overlord);
            overlord.swingMainHand();
            center.getWorld().playSound(center, Sound.ENTITY_PLAYER_ATTACK_STRONG, 1.0f, 0.6f);
        }
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

        w.playSound(c2, Sound.ENTITY_GENERIC_EXPLODE, 1.4f, 0.6f);
        w.spawnParticle(Particle.EXPLOSION, c2.clone().add(0, 1, 0), 20, 4, 1, 4, 0.02);
        w.spawnParticle(Particle.BLOCK, c2, 120, 4, 0.4, 4, 0.1,
                w.getBlockAt(c2.clone().add(0, -1, 0)).getBlockData());

        for (Player p : w.getPlayers()) {
            if (p.getGameMode() == GameMode.SPECTATOR) continue;
            double dist = p.getLocation().distance(c2);
            if (dist > abilityRadius) continue;   // cemberden cikan kurtulur

            p.damage(abilityDamage, overlord);
            launchOutside(p, c2, dist);
        }
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

        if (tickCounter % regenSeconds == 0) {
            if (crystalState != null && crystal != null && !crystal.isDead()) {
                crystalState.heal(crystalRegen);
            }
            if (overlordState != null && overlord != null && !overlord.isDead()) {
                overlordState.heal(overlordRegen);
            }
        }

        if (crystalState != null && crystalBar != null && !crystalBar.isDead()) {
            crystalBar.customName(crystalState.bar());
        }

        if (overlordState != null && overlord != null && !overlord.isDead()) {
            overlord.customName(overlordState.bar());

            Player target = nearestPlayer(overlord.getLocation(), 32);
            if (target != null) {
                overlord.setTarget(target);
                overlord.getPathfinder().moveTo(target, 1.0);
            }

            abilityCounter++;
            if (abilityCounter >= abilityInterval) {
                abilityCounter = 0;
                overlordAbility();
            }
        }

        boolean idle = (crystal == null || crystal.isDead())
                && (overlord == null || overlord.isDead());
        if (idle && System.currentTimeMillis() >= nextSpawnAt) {
            spawnCrystal();
        }
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
        // Oyuncular arasi vurusa karismaz; sadece yaratiklar korunur.
        if (attacker != null && melee
                && victim instanceof LivingEntity && !(victim instanceof Player)
                && victim.getWorld().getName().equalsIgnoreCase(worldName)
                && isMacroHit(attacker)) {
            event.setCancelled(true);
            punishMacro(attacker, victim.getLocation());
            return;
        }

        if (crystal != null && BossState.same(victim, crystal)) {
            event.setCancelled(true);   // vanilla patlamayi engelle, cani biz tutuyoruz
            if (attacker == null) return;

            double dmg = event.getFinalDamage() > 0 ? event.getFinalDamage() : event.getDamage();
            crystal.getWorld().spawnParticle(Particle.CRIT,
                    crystal.getLocation().add(0, 1, 0), 6, 0.4, 0.4, 0.4, 0.05);

            if (crystalState.hit(attacker, dmg)) crystalDied();
            else if (crystalBar != null) crystalBar.customName(crystalState.bar());
            return;
        }

        if (overlord != null && BossState.same(victim, overlord)) {
            event.setCancelled(true);
            if (attacker == null) return;
            double dmg = event.getFinalDamage() > 0 ? event.getFinalDamage() : event.getDamage();
            if (overlordState.hit(attacker, dmg)) {
                overlordDied();
            } else {
                overlord.customName(overlordState.bar());
                overlord.playHurtAnimation(0f);
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

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onExplode(EntityExplodeEvent event) {
        if (event.getEntity().getScoreboardTags().contains(TAG)) {
            event.blockList().clear();
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

        // Odul, secilen oyuncunun ustune duser; oyuncu cevrimdisiysa bosun oldugu yere.
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

            case "spawn" -> {
                removeCrystal();
                setNextSpawn(0L);
                spawnCrystal();
                sender.sendMessage(Component.text("Kristal çağrıldı.", NamedTextColor.GREEN));
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
        sender.sendMessage(Component.text("/cvmobs spawn   |   /cvmobs reload", NamedTextColor.GRAY));
        sender.sendMessage(Component.text("Yaratık adları: crystal, overlord", NamedTextColor.DARK_GRAY));
    }
}
