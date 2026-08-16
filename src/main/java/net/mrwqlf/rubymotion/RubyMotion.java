package net.mrwqlf.rubymotion;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ProxiedCommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * RubyMotion — datapack hareketini "tp" yerine sunucu tarafli HIZ (velocity) ile uretir.
 *
 * Neden: tp, hile korumalarinin (Grim, Matrix, NCP, Vulcan...) gozunde gecersiz konum
 * degisimidir; koruma oyuncuyu geri isinlar (rubber-band). Sunucunun kendi gonderdigi
 * velocity paketi ise korumalar tarafindan tahmin edilip kabul edilir — yay, TNT,
 * havai fisek ve knockback tam olarak boyle calisir.
 *
 * Kullanim (datapack fonksiyonundan, oyuncu olarak calistirilir):
 *   execute as @s at @s run rubyvel push <ivme> <tavan>
 *   execute as @s at @s run rubyvel dash <guc> <yukari>
 *   execute as @s at @s run rubyvel stop
 */
public final class RubyMotion extends JavaPlugin implements CommandExecutor, TabCompleter {

    // Guvenlik tavanlari — komut yanlislikla/kotu niyetle cagirilsa bile bunlari asamaz.
    private static final double MAX_ACCEL = 0.25;   // blok / tick
    private static final double MAX_CAP   = 3.00;   // blok / tick
    private static final double MAX_POWER = 2.00;   // blok / tick
    private static final double MAX_UP    = 1.00;   // blok / tick

    /** Oyuncu basina son havai fisek itisinin zamani (tick). */
    private final Map<UUID, Long> lastBoost = new HashMap<>();

    @Override
    public void onEnable() {
        var cmd = getCommand("rubyvel");
        if (cmd == null) {
            getLogger().severe("rubyvel komutu plugin.yml'de bulunamadi, eklenti kapaniyor.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        cmd.setExecutor(this);
        cmd.setTabCompleter(this);

        // Datapack ile kopru: fonksiyonlar dunya yuklenirken ayristirildigi ve
        // eklentiler daha sonra etkinlestigi icin, datapack /rubyvel cagiramaz --
        // cagirsaydi o fonksiyon "bilinmeyen komut" yuzunden hic kaydedilmezdi.
        // Bunun yerine datapack skor yaziyor, biz her tick okuyup sifirliyoruz.
        ensureObjective("ruby.mdash");
        ensureObjective("ruby.mboost");
        getServer().getScheduler().runTaskTimer(this, this::pollScores, 1L, 1L);

        getLogger().info("RubyMotion etkin — skor koprusu calisiyor (ruby.mdash / ruby.mboost).");
    }

    private void ensureObjective(String name) {
        Scoreboard board = getServer().getScoreboardManager().getMainScoreboard();
        if (board.getObjective(name) == null) {
            board.registerNewObjective(name, Criteria.DUMMY, name);
        }
    }

    private int takeScore(Scoreboard board, Player player, String name) {
        Objective obj = board.getObjective(name);
        if (obj == null) return 0;
        Score score = obj.getScore(player.getName());
        if (!score.isScoreSet()) return 0;
        int v = score.getScore();
        if (v != 0) score.setScore(0);   // istegi tuket
        return v;
    }

    /** Her tick: datapack'in biraktigi istekleri uygula. */
    private void pollScores() {
        Scoreboard board = getServer().getScoreboardManager().getMainScoreboard();
        for (Player player : getServer().getOnlinePlayers()) {
            if (takeScore(board, player, "ruby.mdash") > 0) {
                applyDash(player, 1.15, 0.32);
            }
            int power = takeScore(board, player, "ruby.mboost");
            if (power > 0) {
                applyBoost(player, power);
            }
        }
    }

    private void applyDash(Player player, double power, double up) {
        Vector dir = player.getLocation().getDirection();
        dir.setY(0.0);
        if (dir.lengthSquared() < 1.0E-6) return;
        dir.normalize().multiply(Math.min(power, MAX_POWER));
        dir.setY(Math.max(-MAX_UP, Math.min(MAX_UP, up)));
        player.setVelocity(dir);
    }

    private void applyBoost(Player player, int power) {
        if (!player.isGliding()) return;
        power = Math.max(1, Math.min(3, power));

        long now = player.getWorld().getFullTime();
        Long last = lastBoost.get(player.getUniqueId());
        int gap = switch (power) { case 3 -> 20; case 2 -> 18; default -> 15; };
        if (last != null && now - last < gap) return;
        lastBoost.put(player.getUniqueId(), now);

        ItemStack rocket = new ItemStack(Material.FIREWORK_ROCKET);
        if (rocket.getItemMeta() instanceof FireworkMeta meta) {
            meta.setPower(power);
            rocket.setItemMeta(meta);
        }
        player.fireworkBoost(rocket);
    }

    /** Komutu calistiran oyuncuyu bulur (dogrudan oyuncu, function/execute proxy'si veya isim argumani). */
    private Player resolve(CommandSender sender, String[] args) {
        if (sender instanceof Player p) return p;
        if (sender instanceof ProxiedCommandSender proxy && proxy.getCallee() instanceof Player p) return p;
        // son care: son arguman oyuncu adi olabilir
        if (args.length > 0) {
            Player p = Bukkit.getPlayerExact(args[args.length - 1]);
            if (p != null) return p;
        }
        return null;
    }

    private static double num(String s, double def, double min, double max) {
        try {
            double v = Double.parseDouble(s);
            if (Double.isNaN(v) || Double.isInfinite(v)) return def;
            return Math.max(min, Math.min(max, v));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("Kullanim: /rubyvel push <ivme> <tavan> | dash <guc> <yukari> | boost <1-3> | stop");
            return true;
        }

        Player player = resolve(sender, args);
        if (player == null) {
            sender.sendMessage("RubyMotion: hedef oyuncu bulunamadi.");
            return true;
        }

        switch (args[0].toLowerCase()) {

            // Goktasi: bakis yonunde kademeli hizlanma.
            // Mevcut hiz bakis yonune izdusurulur; tavanin altindaysa aradaki fark
            // kadar (en fazla "ivme") eklenir. Boylece hiz asla tavani asmaz ve
            // hizlanma yumusak kalir.
            case "push" -> {
                double accel = num(args.length > 1 ? args[1] : "", 0.05, 0.0, MAX_ACCEL);
                double cap   = num(args.length > 2 ? args[2] : "", 1.50, 0.0, MAX_CAP);

                Vector dir = player.getLocation().getDirection();
                if (dir.lengthSquared() < 1.0E-6) return true;
                dir.normalize();

                Vector vel = player.getVelocity();
                double along = vel.dot(dir);
                if (along >= cap) return true;

                double add = Math.min(accel, cap - along);
                player.setVelocity(vel.add(dir.multiply(add)));
            }

            // Atilim: tek seferlik ileri itis. Duvar/tavan kontrolu gerekmiyor,
            // carpismayi motorun kendisi hallediyor.
            case "dash" -> applyDash(player,
                    num(args.length > 1 ? args[1] : "", 1.15, 0.0, MAX_POWER),
                    num(args.length > 2 ? args[2] : "", 0.32, -MAX_UP, MAX_UP));

            // Goktasi: elytra hizlanmasi. Velocity DEGIL, vanilla havai fisek itisi.
            //
            // Neden: elytra ile suzulurken her tick velocity yazmak istemcinin kendi
            // ucus fizigini surekli eziyor; hile korumasi tahminini tutturamayip
            // oyuncuyu geri cekiyor (rubber-band). Havai fisek itisi ise oyunun
            // kendi mekanizmasi -- korumalar bunu zaten tanidigi icin hic flag olmuyor.
            case "boost" -> applyBoost(player, (int) num(args.length > 1 ? args[1] : "", 1, 1, 3));

            // Yetenek biterken kalan ivmeyi yumusakca kes.
            case "stop" -> {
                lastBoost.remove(player.getUniqueId());
                Vector vel = player.getVelocity();
                player.setVelocity(vel.multiply(0.6));
            }

            default -> sender.sendMessage("Bilinmeyen alt komut: " + args[0]);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> out = new ArrayList<>();
            for (String s : Arrays.asList("push", "dash", "boost", "stop")) {
                if (s.startsWith(args[0].toLowerCase())) out.add(s);
            }
            return out;
        }
        return List.of();
    }
}
