package net.cursedvalley.mobs;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Bir bosun drop havuzu.
 *
 * Admin bir sandiga esyalari koyar, sandiga bakip komutu calistirir; sandigin
 * icerigi buraya kopyalanir. Boss oldugunde havuzdan RASGELE BIR esya secilir
 * ve "chance" ihtimaliyle verilir -- yani hic dusmeme ihtimali de vardir.
 */
public final class DropTable {

    private final String key;
    private double chance;                 // 0.0 - 1.0
    private final List<ItemStack> items = new ArrayList<>();

    public DropTable(String key) {
        this.key = key;
    }

    public String key()      { return key; }
    public double chance()   { return chance; }
    public int size()        { return items.size(); }
    public boolean isEmpty() { return items.isEmpty(); }

    public void setChance(double c) {
        this.chance = Math.max(0.0, Math.min(1.0, c));
    }

    public void setItems(List<ItemStack> newItems) {
        items.clear();
        for (ItemStack it : newItems) {
            if (it != null && !it.getType().isAir()) {
                items.add(it.clone());
            }
        }
    }

    /** Ihtimali cevirir; dusmezse null doner. */
    public ItemStack roll() {
        if (items.isEmpty()) return null;
        if (ThreadLocalRandom.current().nextDouble() >= chance) return null;
        return items.get(ThreadLocalRandom.current().nextInt(items.size())).clone();
    }

    // ---- kayit / yukleme ----

    @SuppressWarnings("unchecked")
    public void load(FileConfiguration config) {
        setChance(config.getDouble("drops." + key + ".chance", 0.5));
        List<?> raw = config.getList("drops." + key + ".items");
        items.clear();
        if (raw != null) {
            for (Object o : raw) {
                if (o instanceof ItemStack it && !it.getType().isAir()) {
                    items.add(it);
                }
            }
        }
    }

    public void save(FileConfiguration config) {
        config.set("drops." + key + ".chance", chance);
        config.set("drops." + key + ".items", new ArrayList<>(items));
    }
}
