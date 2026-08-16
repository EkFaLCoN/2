package net.cursedvalley.rules;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import net.kyori.adventure.text.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * CursedValleyRules — Cursed Valley dunyasinda elytra ve ender incisi kullanimini kapatir.
 *
 * CursedValleyCore'a HIC dokunmaz; yanina kurulur. Bowl/drop mantigi oldugu gibi kalir.
 * Sadece config'te yazan dunyada calisir, diger dunyalarda hicbir olaya karismaz.
 */
public final class CursedValleyRules extends JavaPlugin implements Listener {

    private String worldName;
    private boolean blockElytra;
    private boolean blockPearl;
    private String msgElytra;
    private String msgPearl;

    /** Mesaj spam'ini onlemek icin oyuncu basina son uyari zamani (ms). */
    private final Map<UUID, Long> lastWarn = new HashMap<>();
    private static final long WARN_GAP_MS = 3000L;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        load();
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("CursedValleyRules etkin — dunya: " + worldName
                + " | elytra: " + (blockElytra ? "kapali" : "serbest")
                + " | ender incisi: " + (blockPearl ? "kapali" : "serbest"));
    }

    private void load() {
        reloadConfig();
        var c = getConfig();
        worldName   = c.getString("world", "cursedvalley");
        blockElytra = c.getBoolean("block-elytra", true);
        blockPearl  = c.getBoolean("block-ender-pearl", true);
        msgElytra   = color(c.getString("message-elytra",
                "&c&lCURSED VALLEY &7- &fBurada elytra ile ucamazsin."));
        msgPearl    = color(c.getString("message-ender-pearl",
                "&c&lCURSED VALLEY &7- &fBurada ender incisi kullanamazsin."));
    }

    private static String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    /** Olay bu dunyada mi? Degilse eklenti hicbir seye karismaz. */
    private boolean inWorld(Entity e) {
        return e != null && e.getWorld().getName().equalsIgnoreCase(worldName);
    }

    /** Uyariyi 3 saniyede bir gosterir; aksi halde ekran dolar. */
    private void warn(Player player, String message) {
        long now = System.currentTimeMillis();
        Long last = lastWarn.get(player.getUniqueId());
        if (last != null && now - last < WARN_GAP_MS) return;
        lastWarn.put(player.getUniqueId(), now);
        player.sendActionBar(Component.text(message));
    }

    // ---------------- ELYTRA ----------------

    /** Suzulmeye gecisi engeller. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onGlide(EntityToggleGlideEvent event) {
        if (!blockElytra) return;
        if (!event.isGliding()) return;              // inise gecisi engelleme
        if (!(event.getEntity() instanceof Player player)) return;
        if (!inWorld(player)) return;

        event.setCancelled(true);
        // Istemci "suzuluyorum" sanmasin diye sunucu tarafinda da kapatiyoruz.
        getServer().getScheduler().runTask(this, () -> player.setGliding(false));
        warn(player, msgElytra);
    }

    /** Elytra'yi gogus slotuna takmayi da engeller (takamazsa hic denemez). */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEquip(InventoryClickEvent event) {
        if (!blockElytra) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!inWorld(player)) return;

        // Oyuncu envanterinde gogus zirhi slotunun ham numarasi 38'dir.
        boolean intoChestSlot = event.getRawSlot() == 38;
        ItemStack incoming = event.getCursor();
        boolean elytraToChest = intoChestSlot
                && incoming != null && incoming.getType() == Material.ELYTRA;

        // Shift+tik ile envanterden gogus slotuna gonderme
        ItemStack clicked = event.getCurrentItem();
        boolean elytraShifted = event.isShiftClick()
                && clicked != null && clicked.getType() == Material.ELYTRA;

        if (elytraToChest || elytraShifted) {
            event.setCancelled(true);
            warn(player, msgElytra);
        }
    }

    /** Sag tikla elytra takmayi engeller. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSwapEquip(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (!inWorld(player)) return;

        ItemStack item = event.getItem();
        if (item == null) return;

        if (blockElytra && item.getType() == Material.ELYTRA
                && event.getHand() == EquipmentSlot.HAND) {
            event.setCancelled(true);
            warn(player, msgElytra);
            return;
        }

        if (blockPearl && item.getType() == Material.ENDER_PEARL) {
            event.setCancelled(true);
            player.updateInventory();   // el animasyonu takili kalmasin
            warn(player, msgPearl);
        }
    }

    // ---------------- ENDER INCISI ----------------

    /** Firlatma anini yakalar (sag tik olayi kacarsa ikinci koruma). */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onLaunch(ProjectileLaunchEvent event) {
        if (!blockPearl) return;
        Projectile projectile = event.getEntity();
        if (!(projectile instanceof EnderPearl)) return;
        if (!inWorld(projectile)) return;

        event.setCancelled(true);
        if (projectile.getShooter() instanceof Player player) {
            player.updateInventory();
            warn(player, msgPearl);
        }
    }

    /** Isinlanma anini da kapatir (baska yoldan atilmis inci varsa). */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (!blockPearl) return;
        if (event.getCause() != PlayerTeleportEvent.TeleportCause.ENDER_PEARL) return;
        if (!inWorld(event.getPlayer())) return;

        event.setCancelled(true);
        warn(event.getPlayer(), msgPearl);
    }

    @Override
    public boolean onCommand(org.bukkit.command.CommandSender sender,
                             org.bukkit.command.Command command,
                             String label, String[] args) {
        load();
        sender.sendMessage(ChatColor.GREEN + "CursedValleyRules ayarlari yeniden yuklendi.");
        return true;
    }
}
