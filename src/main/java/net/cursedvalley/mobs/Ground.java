package net.cursedvalley.mobs;

import org.bukkit.Location;
import org.bukkit.World;

/**
 * Zemin bulma yardimcisi.
 *
 * NEDEN VAR: {@code World#getHighestBlockYAt} o sutundaki EN YUKSEK blogu
 * dondurur, yani dunyanin yuzeyini. Cursed Valley arenasi yeraltinda
 * (Y ~ -45) oldugu icin o fonksiyon arenanin yuzlerce blok ustunu isaret
 * eder. Overlord dalistan cikamiyor, klonlar gorunmuyor, ejder ortaya
 * cikmiyor gibi gorunen hatalarin hepsi bundan kaynaklaniyordu --
 * varliklar kayboluyor degildi, yuzeyde doguyorlardi.
 *
 * Buradaki yontem bunun yerine BIR REFERANS YUKSEKLIGIN cevresinde arar:
 * once yukaridan asagi saglam zemin, bulunamazsa yukari dogru bosluk.
 */
public final class Ground {

    private Ground() {
    }

    /** Referansin ustunde/altinda kac blok aransin. */
    private static final int UP = 8;
    private static final int DOWN = 24;

    /**
     * {@code at} sutununda, {@code refY} yuksekligine en yakin ayakta
     * durulabilir noktayi bulur.
     *
     * @param at   x/z alinacak konum (kendisi degistirilmez)
     * @param refY referans yukseklik -- genelde bossun ya da oyuncunun Y'si
     * @return ayak konumu; uygun yer yoksa refY'nin kendisi
     */
    public static Location findNear(Location at, double refY) {
        World w = at.getWorld();
        if (w == null) return at.clone();

        int x = at.getBlockX();
        int z = at.getBlockZ();
        int start = (int) Math.floor(refY);
        int min = Math.max(w.getMinHeight(), start - DOWN);
        int max = Math.min(w.getMaxHeight() - 2, start + UP);

        // Referansin biraz ustunden asagi inerek ilk saglam zemini bul.
        for (int y = max; y >= min; y--) {
            if (!w.getBlockAt(x, y, z).getType().isSolid()) continue;
            // Ustunde iki blok bosluk olmali ki varlik sikismasin.
            if (w.getBlockAt(x, y + 1, z).getType().isSolid()) continue;
            if (w.getBlockAt(x, y + 2, z).getType().isSolid()) continue;

            Location out = at.clone();
            out.setY(y + 1);
            return out;
        }

        Location out = at.clone();
        out.setY(refY);
        return out;
    }

    /**
     * Zemini bulup uzerine {@code height} blok ekler -- ucan varliklar icin.
     */
    public static Location hoverNear(Location at, double refY, double height) {
        Location g = findNear(at, refY);
        g.setY(g.getY() + height);
        return g;
    }
}
