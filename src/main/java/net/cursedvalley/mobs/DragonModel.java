package net.cursedvalley.mobs;

import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Lizard Dragon'un govdesi.
 *
 * Overlord'daki ile ayni yaklasim: tasiyici varlik (Giant) gorunmez yapilir,
 * uzerine her kemik icin bir item_display takilir. Ejder UCMAZ -- dort ayak
 * uzerinde yurur, kanatlari katli durur.
 *
 * Kemik ofsetleri SCALE = 3.0'a gore blok cinsindendir.
 */
public final class DragonModel {

    public static final String PART_TAG = "cv_drg_part";

    private static final float SCALE = 3.0f;

    /** Bacak boyu -- kalca yuksekligi. */
    private static final float HIP_Y = 2.25f;
    /** Govde merkezinin yuksekligi. */
    private static final float BODY_Y = 3.40f;
    /** On ve arka bacaklarin govde uzerindeki z konumu. */
    private static final float LEG_FRONT_Z = 1.35f;
    private static final float LEG_BACK_Z = -1.35f;
    private static final float LEG_X = 1.05f;

    private final JavaPlugin plugin;
    private final Map<String, ItemDisplay> parts = new LinkedHashMap<>();
    private TextDisplay nameTag;

    private double walked;
    private float lastYaw = Float.MIN_VALUE;
    private Location lastLoc;

    /** Isirma animasyonu. */
    private int biteTick = -1;
    /** Nefes animasyonu: kafa yukari kalkar. */
    private int roarLeft;

    public DragonModel(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    private static final String[] BONES = {
            "torso", "head", "tail", "leg_fr", "leg_fl", "leg_br", "leg_bl", "wing_r", "wing_l"
    };

    // ==================== YASAM DONGUSU ====================

    public void spawn(LivingEntity host) {
        remove();
        World w = host.getWorld();
        Location loc = host.getLocation();

        for (String bone : BONES) {
            ItemDisplay d = w.spawn(loc, ItemDisplay.class, e -> {
                ItemStack it = new ItemStack(org.bukkit.Material.PAPER);
                it.editMeta(m -> m.setItemModel(
                        org.bukkit.NamespacedKey.fromString("cursedvalley:lizarddragon/" + bone)));
                e.setItemStack(it);
                e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
                e.setBillboard(Display.Billboard.FIXED);
                e.setBrightness(new Display.Brightness(12, 12));
                e.setPersistent(false);
                e.setViewRange(4.0f);
                e.setInterpolationDuration(2);
                e.addScoreboardTag(PART_TAG);
            });
            parts.put(bone, d);
        }

        // Gorunmez varligin isim etiketi de gizlenir -> can barini ayri ciziyoruz.
        nameTag = w.spawn(loc.clone().add(0, 1, 0), TextDisplay.class, e -> {
            e.setBillboard(Display.Billboard.CENTER);
            e.setSeeThrough(true);
            e.setPersistent(false);
            e.setViewRange(6.0f);
            e.setBrightness(new Display.Brightness(15, 15));
            e.addScoreboardTag(PART_TAG);
        });
        // Geride kalmasin diye bossa bindiriliyor.
        host.addPassenger(nameTag);
    }

    public void remove() {
        for (ItemDisplay d : parts.values()) {
            if (d != null && !d.isDead()) d.remove();
        }
        parts.clear();
        if (nameTag != null && !nameTag.isDead()) nameTag.remove();
        nameTag = null;
        lastLoc = null;
        lastYaw = Float.MIN_VALUE;
    }

    public static void cleanupLeftovers(World w) {
        if (w == null) return;
        for (Entity e : w.getEntities()) {
            if (e.getScoreboardTags().contains(PART_TAG)) e.remove();
        }
    }

    // ==================== ANIMASYON TETIKLERI ====================

    public void playBite() {
        biteTick = 0;
    }

    public void playRoar(int ticks) {
        roarLeft = Math.max(1, ticks);
    }

    public void setBar(Component text) {
        if (nameTag != null && !nameTag.isDead()) nameTag.text(text);
    }

    // ==================== HER TICK ====================

    public void tick(LivingEntity host) {
        if (parts.isEmpty() || host == null || host.isDead()) return;

        Location loc = host.getLocation();
        float yaw = host.getBodyYaw();

        // Yurume genligi gercek hiza gore
        double step = lastLoc == null || !lastLoc.getWorld().equals(loc.getWorld())
                ? 0 : lastLoc.distance(loc);
        walked += step;
        float amp = (float) Math.min(1.0, step * 7.0);
        float swing = (float) (Math.toRadians(28) * amp * Math.sin(walked * 1.5));

        // Govde nefes alip verir gibi hafifce yukari asagi
        float bob = (float) (Math.sin(walked * 3.0) * 0.06 * amp)
                + (float) (Math.sin(System.currentTimeMillis() / 900.0) * 0.03);

        // Isirma: kafa one dogru atilir
        float headPitch = 0f;
        if (biteTick >= 0) {
            biteTick++;
            headPitch = biteTick < 5
                    ? (float) Math.toRadians(-8 * biteTick)
                    : (float) Math.toRadians(-40 + 8 * (biteTick - 5));
            if (biteTick > 10) biteTick = -1;
        }

        // Kukreme / nefes: kafa yukari kalkar
        if (roarLeft > 0) {
            roarLeft--;
            headPitch = (float) Math.toRadians(32);
        }

        // Konum/yon gercekten degistiyse isinla -- yoksa interpolasyon bozulur.
        boolean moved = lastLoc == null || !lastLoc.getWorld().equals(loc.getWorld())
                || lastLoc.distanceSquared(loc) > 0.0001
                || Math.abs(lastYaw - yaw) > 0.6f;

        Location base = loc.clone();
        base.setPitch(0f);
        base.setYaw(yaw);

        if (moved) {
            for (ItemDisplay d : parts.values()) {
                if (d != null && !d.isDead()) d.teleport(base);
            }
            lastLoc = loc.clone();
            lastYaw = yaw;
        }

        for (Map.Entry<String, ItemDisplay> en : parts.entrySet()) {
            ItemDisplay d = en.getValue();
            if (d == null || d.isDead()) continue;

            float px = 0, py = 0, pz = 0;
            Quaternionf rot = new Quaternionf();

            switch (en.getKey()) {
                case "torso" -> {
                    py = BODY_Y + bob;
                    rot.rotateZ((float) (Math.sin(walked * 1.5) * 0.05 * amp));
                }
                case "head" -> {
                    py = BODY_Y + 0.35f + bob;
                    pz = 1.85f;
                    rot.rotateX(headPitch)
                       .rotateY((float) (Math.sin(walked * 0.75) * 0.10 * amp));
                }
                case "tail" -> {
                    py = BODY_Y - 0.05f + bob;
                    pz = -1.85f;
                    // Kuyruk yurume ile ters faza sallanir
                    rot.rotateY((float) (Math.sin(walked * 1.5 + Math.PI) * 0.28 * (0.4 + amp)));
                }
                // Capraz yuruyus: on-sag ile arka-sol ayni fazda.
                case "leg_fr" -> { px = -LEG_X; py = HIP_Y; pz = LEG_FRONT_Z; rot.rotateX(swing); }
                case "leg_bl" -> { px =  LEG_X; py = HIP_Y; pz = LEG_BACK_Z;  rot.rotateX(swing); }
                case "leg_fl" -> { px =  LEG_X; py = HIP_Y; pz = LEG_FRONT_Z; rot.rotateX(-swing); }
                case "leg_br" -> { px = -LEG_X; py = HIP_Y; pz = LEG_BACK_Z;  rot.rotateX(-swing); }
                case "wing_r" -> {
                    px = -1.15f; py = BODY_Y + 0.55f + bob; pz = 0.4f;
                    // Katli kanat, yururken hafifce kabarir
                    rot.rotateZ((float) Math.toRadians(-18 - 6 * Math.sin(walked * 1.5)));
                }
                case "wing_l" -> {
                    px = 1.15f; py = BODY_Y + 0.55f + bob; pz = 0.4f;
                    rot.rotateZ((float) Math.toRadians(18 + 6 * Math.sin(walked * 1.5)));
                }
                default -> { }
            }

            d.setInterpolationDelay(0);
            d.setInterpolationDuration(2);
            d.setTransformation(new Transformation(
                    new Vector3f(px, py, pz),
                    rot,
                    new Vector3f(SCALE, SCALE, SCALE),
                    new Quaternionf()));
        }
    }
}
