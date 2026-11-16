package dev.ticketreset;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class DataStore {

    public record MuteEntry(String name, String reason, String by, long until) {}
    public record HistoryEntry(long time, String type, String actor, String reason) {}

    private final TicketReset plugin;
    private final File mutesFile, ipsFile, historyFile;

    private final Map<String, MuteEntry> mutes = new ConcurrentHashMap<>();
    private final Map<String, String> displayNames = new HashMap<>();
    private final Map<String, Map<String, Long>> ips = new HashMap<>();
    private final Map<String, List<String>> history = new HashMap<>();

    public DataStore(TicketReset plugin) {
        this.plugin = plugin;
        File dir = plugin.getDataFolder();
        dir.mkdirs();
        mutesFile = new File(dir, "mutes.yml");
        ipsFile = new File(dir, "ips.yml");
        historyFile = new File(dir, "history.yml");
    }

    public synchronized void load() {
        YamlConfiguration m = YamlConfiguration.loadConfiguration(mutesFile);
        for (String key : m.getKeys(false)) {
            ConfigurationSection s = m.getConfigurationSection(key);
            if (s == null) continue;
            mutes.put(key, new MuteEntry(s.getString("name", key), s.getString("reason", ""),
                    s.getString("by", "?"), s.getLong("until", 0)));
        }

        YamlConfiguration i = YamlConfiguration.loadConfiguration(ipsFile);
        for (String key : i.getKeys(false)) {
            ConfigurationSection s = i.getConfigurationSection(key);
            if (s == null) continue;
            displayNames.put(key, s.getString("name", key));
            Map<String, Long> map = new HashMap<>();
            for (String line : s.getStringList("ips")) {
                int idx = line.lastIndexOf('|');
                if (idx <= 0) continue;
                try {
                    map.put(line.substring(0, idx), Long.parseLong(line.substring(idx + 1)));
                } catch (NumberFormatException ignored) {}
            }
            ips.put(key, map);
        }

        YamlConfiguration h = YamlConfiguration.loadConfiguration(historyFile);
        for (String key : h.getKeys(false)) {
            history.put(key, new ArrayList<>(h.getStringList(key)));
        }
    }

    public synchronized void saveNow() {
        YamlConfiguration m = new YamlConfiguration();
        for (var e : mutes.entrySet()) {
            m.set(e.getKey() + ".name", e.getValue().name());
            m.set(e.getKey() + ".reason", e.getValue().reason());
            m.set(e.getKey() + ".by", e.getValue().by());
            m.set(e.getKey() + ".until", e.getValue().until());
        }
        YamlConfiguration i = new YamlConfiguration();
        for (var e : ips.entrySet()) {
            i.set(e.getKey() + ".name", displayNames.getOrDefault(e.getKey(), e.getKey()));
            List<String> lines = new ArrayList<>();
            for (var ip : e.getValue().entrySet()) lines.add(ip.getKey() + "|" + ip.getValue());
            i.set(e.getKey() + ".ips", lines);
        }
        YamlConfiguration h = new YamlConfiguration();
        for (var e : history.entrySet()) h.set(e.getKey(), e.getValue());

        try {
            m.save(mutesFile);
            i.save(ipsFile);
            h.save(historyFile);
        } catch (IOException ex) {
            plugin.getLogger().severe("Could not save data: " + ex.getMessage());
        }
    }

    private void saveAsync() {
        if (plugin.isEnabled()) {
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, this::saveNow);
        } else {
            saveNow();
        }
    }

    public MuteEntry getMute(String name) {
        String key = name.toLowerCase(Locale.ROOT);
        MuteEntry m = mutes.get(key);
        if (m == null) return null;
        if (m.until() > 0 && m.until() <= System.currentTimeMillis()) {
            mutes.remove(key);
            addHistory(name, "MUTE_EXPIRED", "system", "");
            return null;
        }
        return m;
    }

    public void mute(String name, String reason, String by, long until) {
        mutes.put(name.toLowerCase(Locale.ROOT), new MuteEntry(name, reason, by, until));
        saveAsync();
    }

    public boolean unmute(String name) {
        boolean was = mutes.remove(name.toLowerCase(Locale.ROOT)) != null;
        if (was) saveAsync();
        return was;
    }

    public synchronized void recordIp(String name, String ip) {
        String key = name.toLowerCase(Locale.ROOT);
        displayNames.put(key, name);
        ips.computeIfAbsent(key, k -> new HashMap<>()).put(ip, System.currentTimeMillis());
        saveAsync();
    }

    public synchronized boolean hasIpData(String name) {
        Map<String, Long> m = ips.get(name.toLowerCase(Locale.ROOT));
        return m != null && !m.isEmpty();
    }

    public synchronized Set<String> altsOf(String name) {
        String key = name.toLowerCase(Locale.ROOT);
        Map<String, Long> mine = ips.get(key);
        Set<String> result = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        if (mine == null) return result;
        for (var e : ips.entrySet()) {
            if (e.getKey().equals(key)) continue;
            for (String ip : e.getValue().keySet()) {
                if (mine.containsKey(ip)) {
                    result.add(displayNames.getOrDefault(e.getKey(), e.getKey()));
                    break;
                }
            }
        }
        return result;
    }

    public synchronized void addHistory(String name, String type, String actor, String reason) {
        String line = System.currentTimeMillis() + "|" + type + "|" + actor.replace('|', '/') + "|" + reason;
        history.computeIfAbsent(name.toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(line);
        saveAsync();
    }

    public synchronized List<HistoryEntry> getHistory(String name) {
        List<String> lines = history.getOrDefault(name.toLowerCase(Locale.ROOT), List.of());
        List<HistoryEntry> out = new ArrayList<>();
        for (String line : lines) {
            String[] p = line.split("\\|", 4);
            if (p.length < 3) continue;
            try {
                out.add(new HistoryEntry(Long.parseLong(p[0]), p[1], p[2], p.length > 3 ? p[3] : ""));
            } catch (NumberFormatException ignored) {}
        }
        Collections.reverse(out);
        return out;
    }
}
