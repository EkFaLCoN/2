package net.cursedvalley.mobs;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Giant;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.TextDisplay;
import net.kyori.adventure.text.Component;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Overlord'un gorunumu.
 *
 * Vanilla Giant, zombinin dokusunu (entity/zombie/zombie.png) kullanir; onu
 * degistirmek sunucudaki TUM zombileri degistirir ve 26.2'de bir entity'ye
 * ozel model atamanin resourcepack yolu yoktur. Bu yuzden Giant gorunmez
 * yapilir ve uzerine 7 adet item_display "kemik" takilir. Her kemik,
 * item_model bileseni ile kendi modelini gosterir (cursedvalley:overlord/...).
 *
 * Animasyon: her tick setTransformation + interpolation. Datapack'e hic
 * dokunulmaz, komut cagrilmaz.
 */
public final class OverlordModel {

    public static final String PART_TAG = "cv_ovl_part";

    /** Model 46 piksel yuksek; 4.0 olcekte ~11.5 blok (Giant ~12 blok). */
    private static final float SCALE = 4.0f;

    /** Kemik adi + pivotun ayaktan yuksekligi (blok, olcek dahil). */
    private record Bone(String key, float x, float y, boolean limb) {}

    private static final Bone[] BONES = {
            new Bone("torso",  0.00f, 9.00f, false),
            new Bone("head",   0.00f, 9.00f, false),
            new Bone("arm_r", -1.75f, 8.50f, true),
            new Bone("arm_l",  1.75f, 8.50f, true),
            new Bone("leg_r", -0.75f, 4.50f, true),
            new Bone("leg_l",  0.75f, 4.50f, true),
            new Bone("flail",  2.50f, 4.50f, true),
    };

    private final JavaPlugin plugin;
    private final Map<String, ItemDisplay> parts = new LinkedHashMap<>();

    /**
     * Can barinin yazisi. Giant gorunmez yapilinca vanilla isim etiketi de
     * gizlenir; bu yuzden bar ayri bir text_display olarak cizilir.
     */
    private TextDisplay nameTag;

    private Location last;
    private double walkPhase;

    /** Kol animasyonu: hedef aci (radyan) ve o ana kadar gelinen aci. */
    private float armCur, armTarget;
    private boolean armOverride;
    private int overrideLeft;

    public OverlordModel(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    // ==================== YASAM DONGUSU ====================

    public void spawn(Giant giant) {
        remove();
        World w = giant.getWorld();
        Location loc = giant.getLocation();

        for (Bone b : BONES) {
            ItemDisplay d = w.spawn(loc, ItemDisplay.class, e -> {
                e.setItemStack(partItem(b.key()));
                e.setBillboard(Display.Billboard.FIXED);
                e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
                e.setViewRange(3.0f);
                e.setPersistent(false);
                e.setBrightness(new Display.Brightness(12, 12));
                e.setTeleportDuration(2);
                e.setInterpolationDuration(2);
                e.addScoreboardTag(PART_TAG);
            });
            parts.put(b.key(), d);
        }

        nameTag = w.spawn(loc, TextDisplay.class, e -> {
            e.setBillboard(Display.Billboard.CENTER);
            e.setSeeThrough(true);
            e.setPersistent(false);
            e.setViewRange(6.0f);
            e.setTeleportDuration(2);
            e.setBrightness(new Display.Brightness(15, 15));
            e.addScoreboardTag(PART_TAG);
            e.setTransformation(new Transformation(
                    new Vector3f(0f, 12.6f, 0f), new Quaternionf(),
                    new Vector3f(1.6f, 1.6f, 1.6f), new Quaternionf()));
            Component n = giant.customName();
            if (n != null) e.text(n);
        });

        giant.setInvisible(true);
        giant.setCustomNameVisible(false);
        last = null;
        walkPhase = 0;
        armCur = armTarget = 0;
        armOverride = false;
        overrideLeft = 0;
    }

    public void remove() {
        if (nameTag != null && !nameTag.isDead()) nameTag.remove();
        nameTag = null;
        for (ItemDisplay d : parts.values()) {
            if (d != null && !d.isDead()) d.remove();
        }
        parts.clear();
        last = null;
    }

    /** Sunucu acilisinda dunyada kalmis parcalari temizler. */
    public static void cleanupLeftovers(World w) {
        if (w == null) return;
        for (Entity e : w.getEntities()) {
            if (e.getScoreboardTags().contains(PART_TAG)) e.remove();
        }
    }

    private ItemStack partItem(String bone) {
        ItemStack it = new ItemStack(Material.PAPER);
        ItemMeta m = it.getItemMeta();
        m.setItemModel(new NamespacedKey("cursedvalley", "overlord/" + bone));
        it.setItemMeta(m);
        return it;
    }

    // ==================== ANIMASYON TETIKLERI ====================

    /** Yere cakilma: iki kol da tepeye kalkar ve carpma anina kadar orada kalir. */
    public void playSlam() {
        armOverride = true;
        armTarget = (float) Math.toRadians(-170);
        overrideLeft = 80;
    }

    /** Yere carpma ani: kollar asagi savrulur, sonra normale doner. */
    public void slamImpact() {
        armOverride = true;
        armTarget = (float) Math.toRadians(45);
        overrideLeft = 24;
    }

    /** Meteor yagmuru: kollar gokyuzune, yagmur bitene kadar havada. */
    public void playMeteor(int ticks) {
        armOverride = true;
        armTarget = (float) Math.toRadians(-175);
        overrideLeft = Math.max(20, ticks);
    }

    // ==================== HER TICK ====================

    public void tick(Giant giant) {
        if (parts.isEmpty() || giant == null || giant.isDead()) return;

        Location loc = giant.getLocation();
        // Bas yaw'i degil GOVDE yaw'ini kullan; yoksa boss basini cevirdiginde
        // butun model donuyor.
        float yaw = giant.getBodyYaw();

        // yurume fazi: gercek yatay yer degistirmeye gore ilerler
        if (last != null && last.getWorld() == loc.getWorld()) {
            double dx = loc.getX() - last.getX();
            double dz = loc.getZ() - last.getZ();
            walkPhase += Math.sqrt(dx * dx + dz * dz) * 1.6;
        }
        last = loc.clone();

        float swing = (float) (Math.toRadians(32) * Math.sin(walkPhase));

        if (overrideLeft > 0) {
            overrideLeft--;
            if (overrideLeft == 0) {
                armTarget = 0;
                armOverride = false;
            }
        }
        armCur += (armTarget - armCur) * 0.35f;

        float armR = armOverride || Math.abs(armCur) > 0.02f ? armCur : -swing * 0.85f;
        float armL = armOverride || Math.abs(armCur) > 0.02f ? armCur : swing * 0.85f;

        Location base = loc.clone();
        base.setPitch(0f);
        base.setYaw(yaw);

        if (nameTag != null && !nameTag.isDead()) {
            Location tagLoc = loc.clone();
            tagLoc.setPitch(0f);
            tagLoc.setYaw(0f);
            nameTag.teleport(tagLoc);
            Component n = giant.customName();
            if (n != null) nameTag.text(n);
        }

        for (Bone b : BONES) {
            ItemDisplay d = parts.get(b.key());
            if (d == null || d.isDead()) continue;

            float angle = switch (b.key()) {
                case "leg_r" -> swing;
                case "leg_l" -> -swing;
                case "arm_r", "flail" -> armR;
                case "arm_l" -> armL;
                default -> 0f;
            };

            d.teleport(base);
            d.setInterpolationDelay(0);
            d.setInterpolationDuration(2);
            d.setTransformation(new Transformation(
                    new Vector3f(b.x(), b.y(), 0f),
                    b.limb() ? new Quaternionf().rotateX(angle) : new Quaternionf(),
                    new Vector3f(SCALE, SCALE, SCALE),
                    new Quaternionf()));
        }
    }
}
