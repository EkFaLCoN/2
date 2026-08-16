package net.cursedvalley.mobs;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Giant;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * CursedValleyMobsAndMob
 *
 * - 3 saatte bir 0/-47/0 noktasinda End Crystal dogar (50.000 can, 10 sn'de +200)
 * - Kristal kirilinca ayni yerde "Overlord" adli Giant dogar (100.000 can, 10 sn'de +300)
 * - Overlord her 10 saniyede bir r=10 cember uyarisi verir, 1 sn sonra 2 kalp vurup savurur
 *   (yalnizca gorus alaninda oyuncu varken)
 * - Iki bosun droplari da sandiktan ayarlanir, dusme ihtimali vardir, dusenler sohbete yazilir
 *
 * CursedValleyCore ve CursedValleyRules'a dokunmaz; yanlarinda calisir.
 */
public final class CursedValleyMobsAndMob extends JavaPlugin implements Listener {

    // --- ayarlar ---
    private String worldName;
    private int crystalX, crystalY, crystalZ;
    private long intervalTicks;
    private double crystalMaxHp, crystalRegen;
    private double overlordMaxHp, overlordRegen;
    private int regenSeconds;
    private int abilityInterval, abilityWarn;
    private double abilityRadius, abilityDamage, abilityKnockback;

    private final DropTable crystalDrops  = new DropTable("crystal");
    private final DropTable overlordDrops = new DropTable("overlord");

    // --- canli durum ---
    private EnderCrystal crystal;
    private BossState crystalState;
    private Entity crystalBar;          // kristalin ustundeki can yazisi

    private Giant overlord;
    private BossState overlordState;

    private int tickCounter;            // saniye sayaci
    private int abilityCounter;

    private static final String TAG = "cv_boss";

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();
        getServer().getPluginManager().registerEvents(this, this);

        cleanupLeftovers();

        // saniyede bir donen ana dongu
        getServer().getScheduler().runTaskTimer(this, this::everySecond, 20L, 20L);

        // 3 saatte bir kristal
        getServer().getScheduler().runTaskTimer(this, this::spawnCrystal, 100L, intervalTicks);

        getLogger().info("CursedValleyMobsAndMob etkin — dunya: " + worldName
                + " | kristal: " + crystalX + "/" + crystalY + "/" + crystalZ
                + " | dongu: " + (intervalTicks / 72000.0) + " saat");
    }

    @Override
    public void onDisable() {
        removeCrystal();
        if (overlord != null && !overlord.isDead()) overlord.remove();
    }

    private void loadSettings() {
        reloadConfig();
        var c = getConfig();
        worldName     = c.getString("world", "cursedvalley");
        crystalX      = c.getInt("crystal.x", 0);
        crystalY      = c.getInt("crystal.y", -47);
        crystalZ      = c.getInt("crystal.z", 0);
        intervalTicks = Math.max(20L, (long) (c.getDouble("crystal.interval-hours", 3.0) * 72000L));
        crystalMaxHp  = c.getDouble("crystal.max-health", 50000);
        crystalRegen  = c.getDouble("crystal.regen-amount", 200);
        overlordMaxHp = c.getDouble("overlord.max-health", 100000);
        overlordRegen = c.getDouble("overlord.regen-amount", 300);
        regenSeconds  = Math.max(1, c.getInt("regen-seconds", 10));

        abilityInterval  = Math.max(1, c.getInt("overlord.ability.interval-seconds", 10));
        abilityWarn      = Math.max(1, c.getInt("overlord.ability.warn-seconds", 1));
        abilityRadius    = c.getDouble("overlord.ability.radius", 10);
        abilityDamage    = c.getDouble("overlord.ability.damage", 4);
        abilityKnockback = c.getDouble("overlord.ability.knockback", 1.1);

        crystalDrops.load(c);
        overlordDrops.load(c);
    }

    private World world() {
        return Bukkit.getWorld(worldName);
    }

    /** Sunucu cokerse geride kalan boss varliklarini temizler. */
    private void cleanupLeftovers() {
        World w = world();
        if (w == null) return;
        for (Entity e : w.getEntities()) {
            if (e.getScoreboardTags().contains(TAG)) e.remove();
        }
    }

    // ==================== KRISTAL ====================

    private void spawnCrystal() {
        World w = world();
        if (w == null) {
            getLogger().warning("Dunya bulunamadi: " + worldName);
            return;
        }
        if (crystal != null && !crystal.isDead()) return;   // zaten duruyor
        if (overlord != null && !overlord.isDead()) return; // once Overlord bitsin

        Location loc = new Location(w, crystalX + 0.5, crystalY, crystalZ + 0.5);

        // Bolge yuklu degilse varlik hemen kaybolur; chunk'i tutuyoruz.
        Chunk chunk = loc.getChunk();
        chunk.load();
        chunk.addPluginChunkTicket(this);

        crystal = w.spawn(loc, EnderCrystal.class, c -> {
            c.setShowingBottom(true);
            c.setInvulnerable(false);
            c.addScoreboardTag(TAG);
            c.setPersistent(true);
        });
        crystalState = new BossState("KRISTAL", crystalMaxHp);

        // Can barini tasiyan gorunmez isaretci
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

        w.createExplosion(loc, 0.0f, false, false);   // sadece gorsel/ses, blok kirmaz
        w.playSound(loc, Sound.ENTITY_GENERIC_EXPLODE, 1.0f, 0.7f);

        giveDrop(crystalDrops, hitters, loc, "Lanetli Kristal");
        spawnOverlord(loc);
    }

    // ==================== OVERLORD ====================

    private void spawnOverlord(Location loc) {
        World w = loc.getWorld();
        overlord = (Giant) w.spawnEntity(loc, EntityType.GIANT);
        overlord.addScoreboardTag(TAG);
        overlord.setPersistent(true);
        overlord.setRemoveWhenFarAway(false);
        overlord.setCustomNameVisible(true);

        // Vanilla can siniri bu kadar yuksek degerlere uygun degil; can BossState'te tutuluyor.
        var attr = overlord.getAttribute(Attribute.MAX_HEALTH);
        if (attr != null) {
            attr.setBaseValue(1024.0);
            overlord.setHealth(1024.0);
        }

        overlordState = new BossState("OVERLORD", overlordMaxHp);
        overlord.customName(overlordState.bar());

        abilityCounter = 0;

        w.playSound(loc, Sound.ENTITY_WITHER_SPAWN, 1.0f, 0.6f);
        Bukkit.broadcast(Component.text("Kristalden OVERLORD cikti!", NamedTextColor.DARK_RED));
    }

    private void overlordDied() {
        Location loc = overlord.getLocation();
        World w = loc.getWorld();

        List<UUID> hitters = new ArrayList<>(overlordState.attackers().keySet());
        overlord.remove();
        overlord = null;
        overlordState = null;

        w.playSound(loc, Sound.ENTITY_WITHER_DEATH, 1.0f, 0.8f);
        giveDrop(overlordDrops, hitters, loc, "Overlord");

        // Bolge kilidi kalkabilir
        Chunk chunk = loc.getChunk();
        chunk.removePluginChunkTicket(this);
    }

    /** Overlord yetenegi: gorus alanindaki oyunculara cember uyarisi + hasar ve savurma. */
    private void overlordAbility() {
        Location center = overlord.getLocation();
        World w = center.getWorld();

        // Sart: yaricap icinde VE gorus hattinda en az bir oyuncu
        List<Player> seen = new ArrayList<>();
        for (Player p : w.getPlayers()) {
            if (p.getGameMode() == org.bukkit.GameMode.SPECTATOR) continue;
            if (p.getLocation().distance(center) > abilityRadius) continue;
            if (!overlord.hasLineOfSight(p)) continue;
            seen.add(p);
        }
        if (seen.isEmpty()) return;   // kimse gorunmuyorsa yetenek hic calismaz

        drawCircle(w, center, abilityRadius);
        w.playSound(center, Sound.ENTITY_WARDEN_SONIC_CHARGE, 1.2f, 1.4f);

        // 1 saniye sonra vurus
        getServer().getScheduler().runTaskLater(this, () -> {
            if (overlord == null || overlord.isDead()) return;
            Location c2 = overlord.getLocation();
            w.playSound(c2, Sound.ENTITY_GENERIC_EXPLODE, 1.0f, 1.2f);
            w.spawnParticle(Particle.EXPLOSION, c2.clone().add(0, 1, 0), 12, 3, 1, 3, 0.02);

            for (Player p : w.getPlayers()) {
                if (p.getGameMode() == org.bukkit.GameMode.SPECTATOR) continue;
                if (p.getLocation().distance(c2) > abilityRadius) continue;   // ayrilan kurtulur

                p.damage(abilityDamage, overlord);

                Vector away = p.getLocation().toVector().subtract(c2.toVector());
                if (away.lengthSquared() < 0.01) away = new Vector(0, 0, 1);
                away.setY(0).normalize().multiply(abilityKnockback).setY(0.45);
                p.setVelocity(away);
            }
        }, abilityWarn * 20L);
    }

    private void drawCircle(World w, Location center, double radius) {
        var dust = new Particle.DustOptions(Color.fromRGB(255, 40, 40), 2.0f);
        for (int i = 0; i < 90; i++) {
            double a = (Math.PI * 2 / 90) * i;
            double x = center.getX() + radius * Math.cos(a);
            double z = center.getZ() + radius * Math.sin(a);
            Location point = new Location(w, x, center.getY() + 0.4, z);
            w.spawnParticle(Particle.DUST, point, 2, 0.05, 0.4, 0.05, 0, dust);
        }
    }

    // ==================== SANIYELIK DONGU ====================

    private void everySecond() {
        tickCounter++;

        // --- yenilenme ---
        if (tickCounter % regenSeconds == 0) {
            if (crystalState != null && crystal != null && !crystal.isDead()) {
                crystalState.heal(crystalRegen);
            }
            if (overlordState != null && overlord != null && !overlord.isDead()) {
                overlordState.heal(overlordRegen);
            }
        }

        // --- can barlarini tazele ---
        if (crystalState != null && crystalBar != null && !crystalBar.isDead()) {
            crystalBar.customName(crystalState.bar());
        }
        if (overlordState != null && overlord != null && !overlord.isDead()) {
            overlord.customName(overlordState.bar());

            // Giant'in kendi yapay zekasi yok; en yakin oyuncuya dogru yurusun.
            Player target = nearestPlayer(overlord.getLocation(), 32);
            if (target != null) {
                overlord.setTarget(target);
                overlord.getPathfinder().moveTo(target, 1.0);
            }

            // --- yetenek dongusu ---
            abilityCounter++;
            if (abilityCounter >= abilityInterval) {
                abilityCounter = 0;
                overlordAbility();
            }
        }
    }

    private Player nearestPlayer(Location loc, double max) {
        Player best = null;
        double bestDist = max * max;
        for (Player p : loc.getWorld().getPlayers()) {
            if (p.getGameMode() == org.bukkit.GameMode.SPECTATOR) continue;
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
        if (event.getDamager() instanceof Player p) attacker = p;
        else if (event.getDamager() instanceof org.bukkit.entity.Projectile pr
                && pr.getShooter() instanceof Player p2) attacker = p2;

        if (crystal != null && BossState.same(victim, crystal)) {
            event.setCancelled(true);   // vanilla patlamayi engelle, cani biz tutuyoruz
            if (attacker == null) return;
            double dmg = event.getFinalDamage() > 0 ? event.getFinalDamage() : event.getDamage();
            crystal.getWorld().spawnParticle(Particle.CRIT, crystal.getLocation().add(0, 1, 0),
                    6, 0.4, 0.4, 0.4, 0.05);
            if (crystalState.hit(attacker, dmg)) {
                crystalDied();
            } else if (crystalBar != null) {
                crystalBar.customName(crystalState.bar());
            }
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

    /** Kristal cevre hasariyla da patlamasin. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAnyDamage(EntityDamageEvent event) {
        Entity e = event.getEntity();
        if (crystal != null && BossState.same(e, crystal)) {
            if (!(event instanceof EntityDamageByEntityEvent)) event.setCancelled(true);
        } else if (overlord != null && BossState.same(e, overlord)) {
            if (!(event instanceof EntityDamageByEntityEvent)) event.setCancelled(true);
        }
    }

    /** Kristal patlamasi blok kirmasin. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onExplode(EntityExplodeEvent event) {
        if (event.getEntity().getScoreboardTags().contains(TAG)) {
            event.blockList().clear();
        }
    }

    // ==================== DROP ====================

    private void giveDrop(DropTable table, List<UUID> hitters, Location loc, String bossName) {
        if (hitters.isEmpty()) {
            Bukkit.broadcast(Component.text(bossName + " dusurdu ama kimse hasar vermemisti.",
                    NamedTextColor.GRAY));
            return;
        }

        // Vuranlardan RASGELE biri secilir (en cok hasar veren degil).
        UUID chosenId = hitters.get(ThreadLocalRandom.current().nextInt(hitters.size()));
        Player chosen = Bukkit.getPlayer(chosenId);

        ItemStack reward = table.roll();
        if (reward == null) {
            Bukkit.broadcast(Component.text(bossName + " bu sefer hicbir sey birakmadi.",
                    NamedTextColor.GRAY));
            return;
        }

        String who = chosen != null ? chosen.getName() : Bukkit.getOfflinePlayer(chosenId).getName();

        // Esya yere birakilir; Owner alani sayesinde sadece secilen oyuncu alabilir.
        Item dropped = loc.getWorld().dropItemNaturally(loc.clone().add(0, 1, 0), reward);
        dropped.setOwner(chosenId);

        Component itemName = reward.getItemMeta() != null && reward.getItemMeta().hasDisplayName()
                ? reward.getItemMeta().displayName()
                : Component.translatable(reward.getType().translationKey());

        Bukkit.broadcast(Component.text(bossName + " > ", NamedTextColor.DARK_RED)
                .append(Component.text(who == null ? "?" : who, NamedTextColor.YELLOW))
                .append(Component.text(" odulu kazandi: ", NamedTextColor.WHITE))
                .append(Component.text("", NamedTextColor.AQUA).append(itemName))
                .append(Component.text(" x" + reward.getAmount(), NamedTextColor.AQUA)));
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
                sender.sendMessage(Component.text("Ayarlar yeniden yuklendi.", NamedTextColor.GREEN));
            }

            case "spawn" -> {
                if (args.length < 2 || !args[1].equalsIgnoreCase("crystal")) {
                    sender.sendMessage(Component.text("Kullanim: /cvmobs spawn crystal", NamedTextColor.RED));
                    return true;
                }
                removeCrystal();
                spawnCrystal();
                sender.sendMessage(Component.text("Kristal cagrildi.", NamedTextColor.GREEN));
            }

            case "drops" -> handleDrops(sender, args);

            default -> help(sender);
        }
        return true;
    }

    private void handleDrops(CommandSender sender, String[] args) {
        if (args.length < 3) {
            help(sender);
            return;
        }
        DropTable table = switch (args[1].toLowerCase()) {
            case "crystal"  -> crystalDrops;
            case "overlord" -> overlordDrops;
            default -> null;
        };
        if (table == null) {
            sender.sendMessage(Component.text("Boss adi: crystal veya overlord", NamedTextColor.RED));
            return;
        }

        switch (args[2].toLowerCase()) {

            // Bakilan sandigin icerigini drop havuzu yapar
            case "set" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(Component.text("Bu komutu oyuncu olarak kullan.", NamedTextColor.RED));
                    return;
                }
                Block block = player.getTargetBlockExact(6);
                if (block == null || !(block.getState() instanceof Container container)) {
                    sender.sendMessage(Component.text("Bir sandiga bakarak kullan.", NamedTextColor.RED));
                    return;
                }
                List<ItemStack> found = new ArrayList<>();
                for (ItemStack it : container.getInventory().getContents()) {
                    if (it != null && !it.getType().isAir()) found.add(it.clone());
                }
                if (found.isEmpty()) {
                    sender.sendMessage(Component.text("Sandik bos.", NamedTextColor.RED));
                    return;
                }
                table.setItems(found);
                table.save(getConfig());
                saveConfig();
                sender.sendMessage(Component.text(
                        table.key() + " drop havuzu guncellendi: " + found.size() + " esya.",
                        NamedTextColor.GREEN));
            }

            // Dusme ihtimali (yuzde)
            case "chance" -> {
                if (args.length < 4) {
                    sender.sendMessage(Component.text("Kullanim: /cvmobs drops " + table.key()
                            + " chance <0-100>", NamedTextColor.RED));
                    return;
                }
                double pct;
                try {
                    pct = Double.parseDouble(args[3]);
                } catch (NumberFormatException e) {
                    sender.sendMessage(Component.text("Sayi gir: 0-100", NamedTextColor.RED));
                    return;
                }
                table.setChance(pct / 100.0);
                table.save(getConfig());
                saveConfig();
                sender.sendMessage(Component.text(
                        table.key() + " dusme ihtimali: %" + (table.chance() * 100),
                        NamedTextColor.GREEN));
            }

            case "clear" -> {
                table.setItems(new ArrayList<>());
                table.save(getConfig());
                saveConfig();
                sender.sendMessage(Component.text(table.key() + " drop havuzu bosaltildi.",
                        NamedTextColor.GREEN));
            }

            case "info" -> sender.sendMessage(Component.text(
                    table.key() + ": " + table.size() + " esya, ihtimal %"
                            + (table.chance() * 100), NamedTextColor.AQUA));

            default -> help(sender);
        }
    }

    private void help(CommandSender sender) {
        sender.sendMessage(Component.text("--- CursedValleyMobsAndMob ---", NamedTextColor.LIGHT_PURPLE));
        sender.sendMessage(Component.text("/cvmobs spawn crystal", NamedTextColor.GRAY));
        sender.sendMessage(Component.text("/cvmobs drops <crystal|overlord> set   (sandiga bakarak)", NamedTextColor.GRAY));
        sender.sendMessage(Component.text("/cvmobs drops <crystal|overlord> chance <0-100>", NamedTextColor.GRAY));
        sender.sendMessage(Component.text("/cvmobs drops <crystal|overlord> info | clear", NamedTextColor.GRAY));
        sender.sendMessage(Component.text("/cvmobs reload", NamedTextColor.GRAY));
    }
}
