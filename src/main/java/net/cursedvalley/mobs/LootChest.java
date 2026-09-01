package net.cursedvalley.mobs;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.profile.PlayerProfile;
import org.bukkit.profile.PlayerTextures;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.List;
import java.util.UUID;

/**
 * Boss oldugunde yere dusen ganimet sandigi.
 *
 * Neden blok degil: /setblock gercek bir blok koyar. Blok herkese gorunur,
 * oyuncuya ozel gizlenemez, sag tiklaninca envanter acilmaz ve kendi kendine
 * yok olmaz. Bu yuzden sandik UC varliktan olusur:
 *
 *   item_display  -> kafanin kendisi (doner, hafifce salinir)
 *   text_display  -> ustundeki altin yazi
 *   interaction   -> sag tiklamayi yakalayan gorunmez kutu
 *
 * Ucu de kazanan disindaki HERKESTEN hideEntity ile gizlenir; sunucudaki
 * diger oyuncular sandigin varligindan bile haberdar olmaz.
 */
public final class LootChest implements Listener {

    public static final String TAG = "cv_loot";

    private final JavaPlugin plugin;
    private final List<Chest> open = new ArrayList<>();

    /**
     * Bir sandik turu: kendi kafa dokusu, basligi ve suresi.
     * Boss ve siradan yaratik icin iki ayri stil var.
     */
    public record Style(String texture, String title, int seconds) {
        public Style {
            if (title == null || title.isBlank()) title = "Ganimet Sandığı";
            seconds = Math.max(1, seconds);
        }
    }

    /** Kafa esyalari stile gore bir kez uretilip saklanir. */
    private final Map<String, ItemStack> headCache = new HashMap<>();

    public LootChest(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /** Ayarlar yeniden yuklendiginde kafa onbellegini bosalt. */
    public void resetCache() {
        headCache.clear();
    }

    // ==================== SANDIK ====================

    /** Tek bir acik sandik. */
    private final class Chest implements InventoryHolder {
        final UUID owner;
        final ItemDisplay head;
        final TextDisplay label;
        final Interaction hitbox;
        final Inventory inv;
        int ticksLeft;
        final float yaw;
        final String label0;
        double bobPhase;
        boolean closed;

        Chest(UUID owner, ItemDisplay head, TextDisplay label, Interaction hitbox,
              List<ItemStack> loot, int ticks, float yaw, String label0) {
            this.owner = owner;
            this.yaw = yaw;
            this.label0 = label0;
            this.head = head;
            this.label = label;
            this.hitbox = hitbox;
            this.ticksLeft = ticks;
            int rows = Math.max(1, Math.min(6, (loot.size() + 8) / 9));
            this.inv = Bukkit.createInventory(this, rows * 9,
                    Component.text(label0, NamedTextColor.GOLD, TextDecoration.BOLD));
            for (ItemStack it : loot) inv.addItem(it.clone());
        }

        @Override
        public Inventory getInventory() {
            return inv;
        }

        boolean empty() {
            for (ItemStack it : inv.getContents()) {
                if (it != null && !it.getType().isAir()) return false;
            }
            return true;
        }

        void destroy() {
            if (closed) return;
            closed = true;
            // Acik bakan varsa envanterini kapat, yoksa hayalet ekranda kalir.
            for (var viewer : new ArrayList<>(inv.getViewers())) viewer.closeInventory();
            if (head != null && !head.isDead()) head.remove();
            if (label != null && !label.isDead()) label.remove();
            if (hitbox != null && !hitbox.isDead()) hitbox.remove();
        }
    }

    /**
     * Sandigi dogurur. Sadece {@code winner} gorur ve acabilir.
     *
     * @return sandik kurulduysa true; kurulamadiysa (oyuncu cevrimdisi vb.) false,
     *         bu durumda cagiran taraf esyalari eskisi gibi yere dokmeli.
     */
    public boolean drop(Location at, Player winner, List<ItemStack> loot, float bossYaw, Style style) {
        if (winner == null || !winner.isOnline() || loot.isEmpty()) return false;

        World w = at.getWorld();
        if (w == null) return false;
        Location base = spread(at.clone());
        base.setPitch(0f);
        base.setYaw(bossYaw);
        // Modelin yerel +Z'si ileriyi gosterir; Minecraft yaw'i saat yonunun tersi.
        final float rotY = (float) Math.toRadians(-bossYaw);

        ItemStack skull = headItem(style.texture());

        ItemDisplay head = w.spawn(base.clone().add(0, 0.35, 0), ItemDisplay.class, e -> {
            e.setItemStack(skull);
            e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            e.setBillboard(Display.Billboard.FIXED);
            e.setBrightness(new Display.Brightness(15, 15));
            e.setPersistent(false);
            e.setViewRange(4.0f);
            e.setInterpolationDuration(20);
            e.addScoreboardTag(TAG);
            e.setTransformation(new Transformation(
                    new Vector3f(0f, 0f, 0f), new Quaternionf().rotateY(rotY),
                    new Vector3f(1.1f, 1.1f, 1.1f), new Quaternionf()));
        });

        TextDisplay label = w.spawn(base.clone().add(0, 1.15, 0), TextDisplay.class, e -> {
            e.setBillboard(Display.Billboard.CENTER);
            e.setSeeThrough(true);
            e.setPersistent(false);
            e.setViewRange(4.0f);
            e.setBrightness(new Display.Brightness(15, 15));
            e.addScoreboardTag(TAG);
            e.text(Component.text(style.title(), NamedTextColor.GOLD)
                    .decorate(TextDecoration.BOLD, TextDecoration.UNDERLINED));
        });

        Interaction hitbox = w.spawn(base.clone(), Interaction.class, e -> {
            e.setInteractionWidth(1.1f);
            e.setInteractionHeight(1.3f);
            e.setResponsive(true);
            e.setPersistent(false);
            e.addScoreboardTag(TAG);
        });

        Chest c = new Chest(winner.getUniqueId(), head, label, hitbox, loot, style.seconds() * 20, rotY, style.title());
        open.add(c);

        // Kazanan disindaki herkesten gizle.
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!p.getUniqueId().equals(c.owner)) hideFrom(p, c);
        }

        w.playSound(base, Sound.BLOCK_ENDER_CHEST_OPEN, SoundCategory.PLAYERS, 0.9f, 1.4f);
        return true;
    }

    /**
     * Iki yaratik ayni anda olurse sandiklar ust uste binmesin diye
     * dolu noktalari kucuk bir spiralle kaydirir.
     */
    private Location spread(Location want) {
        for (int i = 0; i < 12; i++) {
            boolean clash = false;
            for (Chest c : open) {
                if (c.head == null || c.head.isDead()) continue;
                Location o = c.head.getLocation();
                if (o.getWorld() == want.getWorld() && o.distanceSquared(want) < 1.44) {
                    clash = true;
                    break;
                }
            }
            if (!clash) return want;
            double a = i * (Math.PI / 3);
            double r = 1.3 + (i / 6) * 0.9;
            want.add(Math.cos(a) * r, 0, Math.sin(a) * r);
        }
        return want;
    }

    private void hideFrom(Player p, Chest c) {
        if (c.head != null) p.hideEntity(plugin, c.head);
        if (c.label != null) p.hideEntity(plugin, c.label);
        if (c.hitbox != null) p.hideEntity(plugin, c.hitbox);
    }

    /** Sonradan giren oyuncudan da gizlemek gerekir. */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        for (Chest c : open) {
            if (!p.getUniqueId().equals(c.owner)) hideFrom(p, c);
        }
    }

    private ItemStack headItem(String textureValue) {
        String key = textureValue == null ? "" : textureValue;
        ItemStack cached = headCache.get(key);
        if (cached != null) return cached.clone();

        ItemStack it = new ItemStack(Material.PLAYER_HEAD);
        if (key.isBlank()) { headCache.put(key, it); return it.clone(); }
        try {
            String json = new String(Base64.getDecoder().decode(textureValue), StandardCharsets.UTF_8);
            int i = json.indexOf("\"url\"");
            if (i < 0) return it;
            int a = json.indexOf('"', json.indexOf(':', i) + 1);
            int b = json.indexOf('"', a + 1);
            String raw = json.substring(a + 1, b);
            String hash = raw.substring(raw.lastIndexOf('/') + 1);
            if (hash.length() != 64) {
                plugin.getLogger().warning("Kafa dokusunun hash'i " + hash.length()
                        + " karakter, 64 olmalı — eksik kopyalanmış. Steve kafası çıkacak.");
            }
            URL url = URI.create(raw).toURL();

            PlayerProfile profile = Bukkit.createPlayerProfile(UUID.randomUUID(), "cvloot");
            PlayerTextures tex = profile.getTextures();
            tex.setSkin(url);
            profile.setTextures(tex);

            SkullMeta meta = (SkullMeta) it.getItemMeta();
            meta.setOwnerProfile(profile);
            it.setItemMeta(meta);
        } catch (Exception ex) {
            plugin.getLogger().warning("Ganimet sandığının dokusu okunamadı: " + ex.getMessage());
        }
        headCache.put(key, it);
        return it.clone();
    }

    // ==================== ETKILESIM ====================

    @EventHandler
    public void onInteract(PlayerInteractEntityEvent event) {
        Entity e = event.getRightClicked();
        if (!e.getScoreboardTags().contains(TAG)) return;

        Chest c = byHitbox(e);
        if (c == null) return;
        event.setCancelled(true);

        // Baskasi zaten goremiyor ama yine de emin ol.
        if (!event.getPlayer().getUniqueId().equals(c.owner)) return;

        event.getPlayer().openInventory(c.inv);
        event.getPlayer().playSound(event.getPlayer().getLocation(),
                Sound.BLOCK_CHEST_OPEN, SoundCategory.PLAYERS, 0.8f, 1.2f);
    }

    private Chest byHitbox(Entity e) {
        for (Chest c : open) {
            if (c.hitbox != null && c.hitbox.getUniqueId().equals(e.getUniqueId())) return c;
        }
        return null;
    }

    private Chest byInventory(Inventory inv) {
        return (inv != null && inv.getHolder() instanceof Chest c) ? c : null;
    }

    /** Esyalar bitince sandik hemen kapanir. Kontrol tik sonra yapilir. */
    @EventHandler
    public void onClick(InventoryClickEvent event) {
        Chest c = byInventory(event.getView().getTopInventory());
        if (c == null) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (c.empty()) {
                c.destroy();
                open.remove(c);
            }
        });
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        closeIfEmpty(event.getView().getTopInventory());
    }

    private void closeIfEmpty(Inventory inv) {
        Chest c = byInventory(inv);
        if (c == null) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (c.empty()) {
                c.destroy();
                open.remove(c);
            }
        });
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        Chest c = byInventory(event.getInventory());
        if (c != null && c.empty()) {
            c.destroy();
            open.remove(c);
        }
    }

    // ==================== HER TICK ====================

    /** Donme animasyonu + sure dolunca yok etme. */
    public void tick() {
        for (Iterator<Chest> it = open.iterator(); it.hasNext(); ) {
            Chest c = it.next();

            if (c.head == null || c.head.isDead()) {
                c.destroy();
                it.remove();
                continue;
            }

            if (--c.ticksLeft <= 0) {
                c.destroy();
                it.remove();
                continue;
            }

            // Donmez -- bossun oldugu yone sabit bakar. Sadece hafifce salinir.
            c.bobPhase += 0.07;
            float y = (float) (Math.sin(c.bobPhase) * 0.05);
            c.head.setInterpolationDelay(0);
            c.head.setInterpolationDuration(2);
            c.head.setTransformation(new Transformation(
                    new Vector3f(0f, y, 0f),
                    new Quaternionf().rotateY(c.yaw),
                    new Vector3f(1.1f, 1.1f, 1.1f),
                    new Quaternionf()));

            // Son 5 saniyede sayac gorunur.
            int sec = c.ticksLeft / 20;
            if (c.label != null && !c.label.isDead()) {
                Component base = Component.text(c.label0, NamedTextColor.GOLD)
                        .decorate(TextDecoration.BOLD, TextDecoration.UNDERLINED);
                c.label.text(sec <= 5
                        ? base.append(Component.text("  " + sec + "s", NamedTextColor.RED))
                        : base);
            }
        }
    }

    /** Sunucu kapanirken / acilirken artiklari temizler. */
    public void removeAll() {
        for (Chest c : open) c.destroy();
        open.clear();
    }

    public static void cleanupLeftovers(World w) {
        if (w == null) return;
        for (Entity e : w.getEntities()) {
            if (e.getScoreboardTags().contains(TAG)) e.remove();
        }
    }
}
