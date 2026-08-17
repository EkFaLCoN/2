package net.cursedvalley.mobs;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Drop kayitlari.
 *
 * Her yaratik icin bir liste tutulur; listedeki HER esyanin kendi dusme orani vardir.
 * Boss oldugunde her kayit ayri ayri zar atar -- yani hicbiri dusmeyebilir,
 * biri dusebilir, sansliysa birkaci birden dusebilir.
 *
 * Kayit komutla eklenir: /cvmobs drop add <yaratik> <oran>  (elindeki esya ile)
 */
public final class DropRegistry {

    /** Tek bir drop kaydi: esya + kendi dusme orani (0.0 - 1.0). */
    public record Entry(ItemStack item, double chance) {

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("item", item);
            m.put("chance", chance);
            return m;
        }
    }

    /** yaratik adi (kucuk harf) -> kayitlar */
    private final Map<String, List<Entry>> tables = new LinkedHashMap<>();

    public static String normalize(String mob) {
        return mob == null ? "" : mob.toLowerCase(Locale.ROOT);
    }

    public List<Entry> entries(String mob) {
        return tables.computeIfAbsent(normalize(mob), k -> new ArrayList<>());
    }

    public void add(String mob, ItemStack item, double chance) {
        ItemStack copy = item.clone();
        entries(mob).add(new Entry(copy, Math.max(0.0, Math.min(1.0, chance))));
    }

    /** 1 tabanli sira numarasiyla siler; basarisizsa false. */
    public boolean remove(String mob, int oneBasedIndex) {
        List<Entry> list = entries(mob);
        int i = oneBasedIndex - 1;
        if (i < 0 || i >= list.size()) return false;
        list.remove(i);
        return true;
    }

    public void clear(String mob) {
        entries(mob).clear();
    }

    /** Her kaydin kendi zarini atar; dusen esyalari doner. */
    public List<ItemStack> roll(String mob) {
        List<ItemStack> won = new ArrayList<>();
        for (Entry e : entries(mob)) {
            if (ThreadLocalRandom.current().nextDouble() < e.chance()) {
                won.add(e.item().clone());
            }
        }
        return won;
    }

    // ---- kayit / yukleme ----

    public void load(FileConfiguration config) {
        tables.clear();
        ConfigurationSection root = config.getConfigurationSection("drops");
        if (root == null) return;

        for (String mob : root.getKeys(false)) {
            List<Entry> list = new ArrayList<>();
            List<?> raw = root.getList(mob);
            if (raw != null) {
                for (Object o : raw) {
                    if (!(o instanceof Map<?, ?> map)) continue;
                    Object item = map.get("item");
                    Object chance = map.get("chance");
                    if (item instanceof ItemStack stack && !stack.getType().isAir()) {
                        double c = chance instanceof Number n ? n.doubleValue() : 1.0;
                        list.add(new Entry(stack, Math.max(0.0, Math.min(1.0, c))));
                    }
                }
            }
            tables.put(normalize(mob), list);
        }
    }

    public void save(FileConfiguration config) {
        config.set("drops", null);
        for (Map.Entry<String, List<Entry>> e : tables.entrySet()) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Entry entry : e.getValue()) {
                out.add(entry.toMap());
            }
            config.set("drops." + e.getKey(), out);
        }
    }
}
