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
            // Kollar govdenin (yari genislik 1.75) DISINDA duracak sekilde
            // disari alindi; omuzluk 0.5 blok icten tastigi icin 2.20.
            new Bone("arm_r", -2.20f, 8.50f, true),
            new Bone("arm_l",  2.20f, 8.50f, true),
            // Bacaklar arasinda gercek bosluk olsun diye ayrildi (eskiden
            // yan yana yapisip tek bacak gibi duruyordu).
            new Bone("leg_r", -0.95f, 4.50f, true),
            new Bone("leg_l",  0.95f, 4.50f, true),
            // Topuzun pivotu SOL ELDIR; konumu her tick kolun acisindan
            // hesaplanir (asagi bak), buradaki y kullanilmaz.
            new Bone("flail",  2.80f, 4.50f, true),
    };

    private final JavaPlugin plugin;
    private final Map<String, ItemDisplay> parts = new LinkedHashMap<>();

    /**
     * Can barinin yazisi. Giant gorunmez yapilinca vanilla isim etiketi de
     * gizlenir; bu yuzden bar ayri bir text_display olarak cizilir.
     */
    private TextDisplay nameTag;

    private Location last;
    private Location lastSent;
    private float lastYaw = Float.NaN;
    private double walkPhase;
    private double idlePhase;
    private float walkAmp;

    /** Kol animasyonu: hedef aci (radyan) ve o ana kadar gelinen aci. */
    private float armCur, armTarget;

    /** Yakin dovus vurusu: sol kol (topuzlu el) one savrulur. */
    private int atkTick = -1;
    private float atkAngle;

    /** Omuz-el mesafesi (16 model px * 0.25 blok). */
    private static final float ARM_LEN = 4.0f;
    private static final float SHOULDER_Y = 8.5f;
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
        lastSent = null;
        lastYaw = Float.NaN;
        walkPhase = 0;
        idlePhase = 0;
        walkAmp = 0;
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
        lastSent = null;
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

    /** Yakin dovus: topuzlu kol one savrulur. */
    public void playAttack() {
        atkTick = 0;
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

        // --- yurume fazi ---
        // Faz gercek yer degistirmeye gore ilerler; dururken genlik yumusakca
        // sifira iner (eskiden bacaklar oldugu yerde takiliyordu).
        double speed = 0;
        if (last != null && last.getWorld() == loc.getWorld()) {
            double dx = loc.getX() - last.getX();
            double dz = loc.getZ() - last.getZ();
            speed = Math.sqrt(dx * dx + dz * dz);
        }
        last = loc.clone();
        walkPhase += Math.min(0.55, speed * 3.4);
        walkAmp += ((speed > 0.015 ? 1f : 0f) - walkAmp) * 0.18f;

        float swing = (float) (Math.toRadians(38) * Math.sin(walkPhase)) * walkAmp;
        float idle = (float) (Math.toRadians(3) * Math.sin(idlePhase));
        idlePhase += 0.06;

        // --- kol animasyonu (yetenekler) ---
        if (overrideLeft > 0) {
            overrideLeft--;
            if (overrideLeft == 0) {
                armTarget = 0;
                armOverride = false;
            }
        }
        armCur += (armTarget - armCur) * 0.30f;

        // vurus egrisi: 3 tick'te one, 6 tick'te geri
        if (atkTick >= 0) {
            atkTick++;
            float peak = (float) Math.toRadians(-80);
            if (atkTick <= 3) atkAngle = peak * (atkTick / 3f);
            else if (atkTick <= 9) atkAngle = peak * ((9 - atkTick) / 6f);
            else { atkTick = -1; atkAngle = 0f; }
        }
        boolean armBusy = armOverride || Math.abs(armCur) > 0.03f;

        float armR = armBusy ? armCur : -swing * 0.8f + idle;
        float armL = armBusy ? armCur : swing * 0.8f - idle;
        if (atkTick >= 0) armL = atkAngle;

        // --- govde: yalnizca gerektiginde isinla ---
        // Her tick teleport cagirmak interpolasyonu sifirliyor ve parcalar
        // birbirinden ayri kayiyordu.
        boolean moved = lastSent == null
                || lastSent.getWorld() != loc.getWorld()
                || lastSent.distanceSquared(loc) > 0.0004
                || Math.abs(lastYaw - yaw) > 0.6f;

        Location base = loc.clone();
        base.setPitch(0f);
        base.setYaw(yaw);

        if (moved) {
            lastSent = loc.clone();
            lastYaw = yaw;
            for (ItemDisplay d : parts.values()) {
                if (d != null && !d.isDead()) d.teleport(base);
            }
            if (nameTag != null && !nameTag.isDead()) {
                Location tagLoc = loc.clone();
                tagLoc.setPitch(0f);
                tagLoc.setYaw(0f);
                nameTag.teleport(tagLoc);
            }
        }

        if (nameTag != null && !nameTag.isDead()) {
            Component n = giant.customName();
            if (n != null) nameTag.text(n);
        }

        float bob = (float) (Math.sin(walkPhase * 2) * 0.07) * walkAmp;

        for (Bone b : BONES) {
            ItemDisplay d = parts.get(b.key());
            if (d == null || d.isDead()) continue;

            float angle = switch (b.key()) {
                case "leg_r" -> swing;
                case "leg_l" -> -swing;
                case "arm_r" -> armR;
                // Topuz SOL elde tutuluyor (pivot +x); sag kola baglanınca
                // havada tek basina saliniyordu.
                case "arm_l", "flail" -> armL;
                default -> 0f;
            };

            float y = b.y() + (b.key().startsWith("leg") ? 0f : bob);
            float z = 0f;

            // Topuz sol ELDE durur. El, omuz etrafinda donen kolun ucundadir;
            // bu yuzden konumu kolun acisindan hesaplanir. (Eskiden sabit
            // pivotta durdugu icin kol kalkinca havada asili kaliyordu.)
            if (b.key().equals("flail")) {
                float th = armL;
                y = SHOULDER_Y - ARM_LEN * (float) Math.cos(th) + bob;
                z = -ARM_LEN * (float) Math.sin(th);
            }

            d.setInterpolationDelay(0);
            d.setInterpolationDuration(2);
            d.setTransformation(new Transformation(
                    new Vector3f(b.x(), y, z),
                    b.limb() ? new Quaternionf().rotateX(angle) : new Quaternionf().rotateZ(idle * 0.4f),
                    new Vector3f(SCALE, SCALE, SCALE),
                    new Quaternionf()));
        }
    }
}
