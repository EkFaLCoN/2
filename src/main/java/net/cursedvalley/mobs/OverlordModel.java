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
    private static final float BOSS_SCALE = 4.0f;
    /** Bu rigin olcegi. Bosslar 4.0, mini klonlar daha kucuk. */
    private final float scale;

    /** Kemik adi + pivotun ayaktan yuksekligi (blok, olcek dahil). */
    private record Bone(String key, float x, float y, boolean limb) {}

    /** Bel yuksekligi (bacak boyu). Govde ve bacaklar buradan baslar. */
    private static final float WAIST_Y = 4.5f;
    /** Belden omuza mesafe (govde boyu). */
    private static final float TORSO_LEN = 4.0f;
    /** Belden kafanin altina mesafe. Govde ustunde bir boyun payi vardir,
     *  yoksa kafa dogrudan govdeye yapisik gorunur. */
    private static final float NECK_LEN = 4.65f;
    /** Omuzdan ele mesafe (kol boyu). */
    private static final float ARM_LEN = 4.0f;
    /** Omuz ve kalca yanal ofsetleri. */
    private static final float ARM_X = 2.40f;
    private static final float LEG_X = 0.80f;

    private static final Bone[] BONES = {
            new Bone("torso",  0.00f, 0f, false),
            new Bone("head",   0.00f, 0f, false),
            new Bone("arm_r", -ARM_X, 0f, true),
            new Bone("arm_l",  ARM_X, 0f, true),
            new Bone("leg_r", -LEG_X, 0f, true),
            new Bone("leg_l",  LEG_X, 0f, true),
            new Bone("flail",  ARM_X, 0f, true),
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

    private boolean armOverride;
    private int overrideLeft;

    /** Gurz Firtinasi: iki elle tutup donme. */
    private int stormLeft;
    private double stormSpin;
    /** Firtina sonrasi sarsilma. */
    private int staggerLeft;

    public OverlordModel(JavaPlugin plugin) {
        this(plugin, BOSS_SCALE);
    }

    public OverlordModel(JavaPlugin plugin, float scale) {
        this.plugin = plugin;
        this.scale = scale;
    }

    /** Olcek carpani: kemik ofsetleri BOSS_SCALE'e gore yazildi. */
    private float k() {
        return scale / BOSS_SCALE;
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

    /** Dalis gibi anlarda tum kemikleri gizler/gosterir. */
    public void hide(boolean hidden) {
        for (ItemDisplay d : parts.values()) {
            if (d != null && !d.isDead()) d.setViewRange(hidden ? 0f : 3f);
        }
        if (nameTag != null && !nameTag.isDead()) {
            nameTag.setViewRange(hidden ? 0f : 3f);
        }
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

    /**
     * Gurz Firtinasi duruşu: gurz IKI ELLE tutulur (iki kol da one uzanir,
     * gurz ellerin arasinda ortada durur) ve boss kendi ekseninde doner.
     */
    public void playStorm(int ticks) {
        stormLeft = Math.max(1, ticks);
        maceActive = false;
        maceCur = 0f;
        atkTick = -1;
    }

    /** Firtinadan sonra sersemleme: govde saga sola sallanir. */
    public void playStagger(int ticks) {
        staggerLeft = Math.max(1, ticks);
        stormLeft = 0;
    }

    public boolean stormActive() {
        return stormLeft > 0;
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
        // --- Gurz Firtinasi ---
        boolean twoHanded = false;
        float spinDeg = 0f;
        if (stormLeft > 0) {
            stormLeft--;
            twoHanded = true;
            stormSpin += 26.0;              // tur basina ~14 tick
            spinDeg = (float) stormSpin;
            if (stormLeft == 0) stormSpin = 0;
        }

        // --- sarsilma ---
        float shake = 0f;
        if (staggerLeft > 0) {
            staggerLeft--;
            shake = (float) (Math.toRadians(9) * Math.sin(staggerLeft * 0.9));
        }

        boolean armBusy = armOverride || Math.abs(armCur) > 0.03f;

        float armR = armBusy ? armCur : -swing * 0.8f + idle;
        float armL = armBusy ? armCur : swing * 0.8f - idle;
        if (atkTick >= 0) armL = atkAngle;

        // Iki elle tutus: her iki kol da one uzanir, gurz ortada kalir.
        if (twoHanded) {
            armR = (float) Math.toRadians(-92);
            armL = (float) Math.toRadians(-92);
        }

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
            if (!twoHanded) armL = maceCur;
        }

        // --- govde: yalnizca gerektiginde isinla ---
        // Her tick teleport cagirmak interpolasyonu sifirliyor ve parcalar
        // birbirinden ayri kayiyordu.
        boolean moved = lastSent == null
                || lastSent.getWorld() != loc.getWorld()
                || lastSent.distanceSquared(loc) > 0.0004
                || Math.abs(lastYaw - (yaw + spinDeg)) > 0.6f;

        Location base = loc.clone();
        base.setPitch(0f);
        base.setYaw(yaw + spinDeg);

        if (moved) {
            lastSent = loc.clone();
            lastYaw = yaw + spinDeg;
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

        // ---- iskelet hiyerarsisi ----
        // Bel referans noktadir. Govde belden doner; omuz ve boyun govdenin
        // ucundadir, yani govde egilince onlar da tasinir. El omuzun ucundadir,
        // gurz de elin icindedir. Boylece hicbir parca digerinden kopmaz.
        float drop  = crouchCur * 0.9f;
        float waist = WAIST_Y - drop + bob;
        float cosL  = (float) Math.cos(leanCur);
        float sinL  = (float) Math.sin(leanCur);

        float neckY     = waist + NECK_LEN * cosL;
        float neckZ     =         NECK_LEN * sinL;
        float shoulderY = waist + TORSO_LEN * cosL;
        float shoulderZ =         TORSO_LEN * sinL;

        // el = omuz + omuzdan asagi sarkan kolun ucu (once X, sonra Y donusu)
        float th   = leanCur + armL;
        float sinT = (float) Math.sin(th), cosT = (float) Math.cos(th);
        float sinY = (float) Math.sin(atkYaw), cosY = (float) Math.cos(atkYaw);
        // Iki elle tutarken gurz govdenin tam onunde, ellerin ortasinda durur.
        float maceBaseX = twoHanded ? 0f : ARM_X;
        float handX = maceBaseX - ARM_LEN * sinT * sinY;
        float handY = shoulderY - ARM_LEN * cosT;
        float handZ = shoulderZ - ARM_LEN * sinT * cosY;

        for (Bone b : BONES) {
            ItemDisplay d = parts.get(b.key());
            if (d == null || d.isDead()) continue;

            float px = 0f, py = 0f, pz = 0f;
            Quaternionf rot = null;

            switch (b.key()) {
                case "leg_r", "leg_l" -> {
                    float a = b.key().equals("leg_r") ? swing : -swing;
                    px = b.x(); py = WAIST_Y - drop; pz = 0f;
                    rot = new Quaternionf().rotateX(a - crouchCur * 0.45f);
                }
                case "torso" -> {
                    px = 0f; py = waist; pz = 0f;
                    rot = new Quaternionf().rotateX(leanCur).rotateZ(idle * 0.4f + shake);
                }
                case "head" -> {
                    px = 0f; py = neckY; pz = neckZ;
                    rot = new Quaternionf().rotateX(leanCur).rotateZ(idle * 0.4f + shake * 1.4f);
                }
                case "arm_r" -> {
                    px = b.x(); py = shoulderY; pz = shoulderZ;
                    rot = new Quaternionf().rotateX(leanCur + armR);
                }
                case "arm_l" -> {
                    px = b.x(); py = shoulderY; pz = shoulderZ;
                    rot = new Quaternionf().rotateY(atkYaw).rotateX(th);
                }
                case "flail" -> {
                    px = handX; py = handY; pz = handZ;
                    rot = new Quaternionf().rotateY(atkYaw).rotateX(th);
                }
                default -> { }
            }
            if (rot == null) continue;

            d.setInterpolationDelay(0);
            d.setInterpolationDuration(2);
            float kk = k();
            d.setTransformation(new Transformation(
                    new Vector3f(px * kk, py * kk, pz * kk),
                    rot,
                    new Vector3f(scale, scale, scale),
                    new Quaternionf()));
        }
    }
}
