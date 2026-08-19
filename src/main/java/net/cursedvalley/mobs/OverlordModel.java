package net.cursedvalley.mobs;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
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

    /** Omuz pivotunun govde merkezine uzakligi. */
    private static final float SHOULDER_X = 1.80f;
    /**
     * Yumrugun merkezi, omuz pivotunun ne kadar DISINDA. arm_l modelinde
     * fist_l kutusu x 7.5..13.5 -> merkez 10.5 -> pivottan +2.5 px = 0.625 blok.
     * Gurzun oturmasi gereken nokta tam burasi.
     */
    private static final float HAND_LAT = 0.625f;

    /** Kemik adi + pivotun ayaktan yuksekligi (blok, olcek dahil). */
    private record Bone(String key, float x, float y, boolean limb) {}

    private static final Bone[] BONES = {
            new Bone("torso",  0.00f, 9.00f, false),
            new Bone("head",   0.00f, 9.00f, false),
            // ONEMLI: bu degerler MODELIN kendi kutu yerlesimine baglidir.
            // arm_l/leg_l kutulari pivotun +x tarafinda (disa dogru), arm_r/leg_r
            // -x tarafinda duruyor. Yani pivot = uzvun ICE bakan kenari.
            // Eski ±2.55 / ±1.55 degerleri kutulari ICE giden bir pakete gore
            // ayarlanmisti; o paketle uzuvlar govdeye yapisiyor, gurz ise bir kol
            // genisligi kadar disarida kaliyordu.
            new Bone("arm_r", -SHOULDER_X, 8.50f, true),
            new Bone("arm_l",  SHOULDER_X, 8.50f, true),
            new Bone("leg_r", -0.15f, 4.50f, true),
            new Bone("leg_l",  0.15f, 4.50f, true),
            // Gurzun pivotu SOL ELDIR; konumu her tick kolun acisindan
            // hesaplanir (asagi bak), buradaki x/y sadece ilk kare icin.
            new Bone("flail",  SHOULDER_X + HAND_LAT, 4.50f, true),
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
    private float atkAngle;      // kolun one kalkma acisi (X ekseni)
    private float atkYaw;        // soldan saga savurma (Y ekseni)

    /** Yer sarsmada dizler bukulur, boss cokup dogrulur. */
    private float crouchCur, crouchTarget;
    private int crouchLeft;

    /** Gurz kolu: havaya kaldirma ve yere indirme (sicrama yetenegi). */
    private boolean maceActive;
    private float maceCur, maceTarget, maceSpeed = 0.25f;
    private int maceLeft;

    /** Gurzle yere vururken govde one egilir, hemen ardindan dogrulur. */
    private float leanCur, leanTarget;
    private int leanLeft;

    /** Adim sesi icin: yurume fazinin kacinci yarim donusunde oldugumuz. */
    private long lastStep = Long.MIN_VALUE;

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

        // Can bari bossun UZERINE BINER (passenger). Elle isinlanirsa boss
        // hareket ettiginde geride kaliyor ve alakasiz yerlerde duruyordu.
        nameTag = w.spawn(loc.clone().add(0, 1, 0), TextDisplay.class, e -> {
            e.setBillboard(Display.Billboard.CENTER);
            e.setSeeThrough(true);
            e.setPersistent(false);
            e.setViewRange(6.0f);
            e.setTeleportDuration(2);
            e.setBrightness(new Display.Brightness(15, 15));
            e.addScoreboardTag(PART_TAG);
            e.setTransformation(new Transformation(
                    new Vector3f(0f, 3.4f, 0f), new Quaternionf(),
                    new Vector3f(1.6f, 1.6f, 1.6f), new Quaternionf()));
            Component n = giant.customName();
            if (n != null) e.text(n);
        });

        giant.addPassenger(nameTag);

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

    /** Sicrama: gurz havaya kalkar, inise kadar orada bekler. */
    public void playSlamRaise() {
        maceActive = true;
        maceTarget = (float) Math.toRadians(-125);
        maceSpeed = 0.22f;
        maceLeft = 120;
    }

    /** Yere carpma: gurz onunde yere iner, govde one egilir. */
    public void playSlamStrike() {
        maceActive = true;
        maceTarget = (float) Math.toRadians(-8);
        maceSpeed = 0.60f;
        maceLeft = 18;
        leanTarget = (float) Math.toRadians(34);   // one egil
        leanLeft = 10;                             // 10 tick sonra dogrul
        crouchTarget = 1f;                         // dizler bukulur, boss coker
        crouchLeft = 12;
    }

    /**
     * Meteor cagirma duruşu: SADECE gurzlu kol havaya kalkar, verilen sure
     * boyunca yukarida kalir, sonra iner ve boss dovuse devam eder.
     */
    public void playMeteorCast(int ticks) {
        maceActive = true;
        maceTarget = (float) Math.toRadians(-165);
        maceSpeed = 0.42f;
        maceLeft = Math.max(10, ticks);
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

        // Vurus: gurz SOLDAN SAGA yatay savrulur.
        // atkAngle kolu one kaldirir (yere paralel), atkYaw yatay supurmeyi yapar.
        if (atkTick >= 0) {
            atkTick++;
            float lift = (float) Math.toRadians(-78);
            float from = (float) Math.toRadians(85);    // solda
            float to   = (float) Math.toRadians(-70);   // sagda
            if (atkTick <= 3) {                          // hazirlik: kol kalkar, sola gider
                float t = atkTick / 3f;
                atkAngle = lift * t;
                atkYaw = from * t;
            } else if (atkTick <= 8) {                   // savurma: hizli supurme
                float t = (atkTick - 3) / 5f;
                atkAngle = lift;
                atkYaw = from + (to - from) * t;
            } else if (atkTick <= 14) {                  // toparlanma
                float t = (14 - atkTick) / 6f;
                atkAngle = lift * t;
                atkYaw = to * t;
            } else {
                atkTick = -1; atkAngle = 0f; atkYaw = 0f;
            }
        }
        boolean armBusy = armOverride || Math.abs(armCur) > 0.03f;

        float armR = armBusy ? armCur : -swing * 0.8f + idle;
        float armL = armBusy ? armCur : swing * 0.8f - idle;
        if (atkTick >= 0) armL = atkAngle;

        // dizlerin bukulmesi (yer sarsma)
        if (crouchLeft > 0 && --crouchLeft == 0) crouchTarget = 0f;
        crouchCur += (crouchTarget - crouchCur) * (crouchTarget == 0f ? 0.18f : 0.55f);

        // govde egilmesi
        if (leanLeft > 0 && --leanLeft == 0) leanTarget = 0f;
        leanCur += (leanTarget - leanCur) * (leanTarget == 0f ? 0.32f : 0.55f);

        // gurz kolu her seyin onunde gelir
        if (maceActive) {
            if (maceLeft > 0 && --maceLeft == 0) {
                maceTarget = 0f;
                maceSpeed = 0.20f;
            }
            maceCur += (maceTarget - maceCur) * maceSpeed;
            if (maceLeft == 0 && Math.abs(maceCur) < 0.04f) {
                maceActive = false;
                maceCur = 0f;
            }
            armL = maceCur;
        }

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
        }

        if (nameTag != null && !nameTag.isDead()) {
            Component n = giant.customName();
            if (n != null) nameTag.text(n);
        }

        float bob = (float) (Math.sin(walkPhase * 2) * 0.07) * walkAmp;

        // agir adim sesi: her yarim salinimda bir ayak yere basar
        long half = (long) Math.floor(walkPhase / Math.PI);
        if (walkAmp > 0.45f && half != lastStep) {
            lastStep = half;
            World w2 = loc.getWorld();
            w2.playSound(loc, Sound.ENTITY_RAVAGER_STEP, SoundCategory.HOSTILE, 1.8f, 0.45f);
            w2.playSound(loc, Sound.ENTITY_IRON_GOLEM_STEP, SoundCategory.HOSTILE, 1.4f, 0.5f);
        }

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

            boolean isLeg = b.key().startsWith("leg");
            // comelme: butun govde asagi iner, bacaklar disa acilir
            float drop = crouchCur * 0.9f;
            float y = b.y() + (isLeg ? 0f : bob) - drop;
            float z = 0f;

            // Topuz sol ELDE durur. El, omuz etrafinda donen kolun ucundadir;
            // bu yuzden konumu kolun acisindan hesaplanir. (Eskiden sabit
            // pivotta durdugu icin kol kalkinca havada asili kaliyordu.)
            float swingYaw = (b.key().equals("arm_l") || b.key().equals("flail")) ? atkYaw : 0f;

            if (b.key().equals("flail")) {
                // El, once X ekseninde (th) sonra Y ekseninde (swingYaw) donen
                // kolun ucundadir. Gurz tam oraya oturur.
                // El = omuz + (yanal kayma, kol boyu) vektorunun once X sonra Y
                // ekseninde dondurulmus hali. Yanal kayma da Y donusunden
                // etkilenir; eskiden sabit birakildigi icin yatay savurmada
                // gurz elden kopuyordu.
                float th = armL;
                double sy = Math.sin(swingYaw), cy = Math.cos(swingYaw);
                double st = Math.sin(th), ct = Math.cos(th);
                float hx = (float) (HAND_LAT * cy - ARM_LEN * st * sy);
                float hz = (float) (-HAND_LAT * sy - ARM_LEN * st * cy);
                y = SHOULDER_Y - ARM_LEN * (float) ct + bob - drop;
                z = hz;
                d.setInterpolationDelay(0);
                d.setInterpolationDuration(2);
                d.setTransformation(new Transformation(
                        new Vector3f(SHOULDER_X + hx, y, z),
                        new Quaternionf().rotateY(swingYaw).rotateX(th),
                        new Vector3f(SCALE, SCALE, SCALE),
                        new Quaternionf()));
                continue;
            }

            Quaternionf rot;
            if (isLeg) {
                // comelirken dizler disa/one acilsin
                rot = new Quaternionf().rotateX(angle - crouchCur * 0.45f);
            } else if (b.limb()) {
                rot = new Quaternionf().rotateY(swingYaw).rotateX(angle);
            } else {
                // govde ve kafa: egilme + hafif idle salinim
                rot = new Quaternionf().rotateX(leanCur).rotateZ(idle * 0.4f);
                z += (float) (Math.sin(leanCur) * 1.2);
                y -= (float) ((1 - Math.cos(leanCur)) * 1.2);
            }

            d.setInterpolationDelay(0);
            d.setInterpolationDuration(2);
            d.setTransformation(new Transformation(
                    new Vector3f(b.x(), y, z),
                    rot,
                    new Vector3f(SCALE, SCALE, SCALE),
                    new Quaternionf()));
        }
    }
}
