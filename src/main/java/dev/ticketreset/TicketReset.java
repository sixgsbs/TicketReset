package dev.ticketreset;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

public final class TicketReset extends JavaPlugin implements Listener {

    private static final String ALPHABET = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9_]{3,16}$");

    private final SecureRandom random = new SecureRandom();
    private JDA jda;
    private final Set<Long> staffRoleIds = new HashSet<>();
    private final Set<Long> ipRoleIds = new HashSet<>();

    private File playersFile;
    private File historyFile;
    private YamlConfiguration players;
    private YamlConfiguration history;
    private boolean dirty;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        String token = getConfig().getString("discord.token", "");
        String guildId = getConfig().getString("discord.guild-id", "");

        try {
            for (String id : getConfig().getStringList("discord.staff-role-ids")) {
                staffRoleIds.add(Long.parseLong(id.trim()));
            }
            Long.parseLong(guildId.trim());
        } catch (NumberFormatException e) {
            getLogger().severe("guild-id and staff-role-ids in config.yml must be numbers. Disabling.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        if (staffRoleIds.isEmpty()) {
            getLogger().severe("Add at least one ID under discord.staff-role-ids. Disabling.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        if (token.isBlank() || token.startsWith("PUT_")) {
            getLogger().severe("Set discord.token in config.yml. Disabling.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        // optional: only these roles may use /ip and /alts (default: the staff roles)
        for (String id : getConfig().getStringList("discord.ip-role-ids")) {
            try {
                ipRoleIds.add(Long.parseLong(id.trim()));
            } catch (NumberFormatException ignored) {
            }
        }

        getDataFolder().mkdirs();
        playersFile = new File(getDataFolder(), "players.yml");
        historyFile = new File(getDataFolder(), "history.yml");
        players = YamlConfiguration.loadConfiguration(playersFile);
        history = YamlConfiguration.loadConfiguration(historyFile);
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getScheduler().runTaskTimer(this, this::saveIfDirty, 6000L, 6000L);

        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            try {
                jda = JDABuilder.createLight(token).addEventListeners(new ResetListener()).build().awaitReady();
                Guild guild = jda.getGuildById(guildId.trim());
                if (guild == null) {
                    getLogger().severe("Bot is not in the guild " + guildId + ". Invite it first.");
                    return;
                }
                guild.updateCommands().addCommands(
                        Commands.slash("resetpass", "Give a player a temporary password")
                                .addOption(OptionType.STRING, "player", "Minecraft username", true)
                                .setGuildOnly(true),
                        Commands.slash("ban", "Ban a player from the Minecraft server")
                                .addOption(OptionType.STRING, "player", "Minecraft username", true)
                                .addOption(OptionType.STRING, "reason", "Reason for the ban", false)
                                .setGuildOnly(true),
                        Commands.slash("unban", "Unban a player from the Minecraft server")
                                .addOption(OptionType.STRING, "player", "Minecraft username", true)
                                .setGuildOnly(true),
                        Commands.slash("history", "Join info and punishment history of a player")
                                .addOption(OptionType.STRING, "player", "Minecraft username", true)
                                .setGuildOnly(true),
                        Commands.slash("alts", "Accounts that share an IP with a player")
                                .addOption(OptionType.STRING, "player", "Minecraft username", true)
                                .setGuildOnly(true),
                        Commands.slash("ip", "Known IP addresses of a player")
                                .addOption(OptionType.STRING, "player", "Minecraft username", true)
                                .setGuildOnly(true)
                ).queue();
                getLogger().info("Discord bot ready.");
            } catch (Exception e) {
                getLogger().severe("Discord bot failed to start: " + e.getMessage());
            }
        });
    }

    @Override
    public void onDisable() {
        if (players != null) saveIfDirty();
        if (jda != null) jda.shutdown();
    }

    /* ------------------------------------------------------------ data */

    private static String key(String name) {
        return name.toLowerCase().replace('.', '~');
    }

    private void saveIfDirty() {
        if (!dirty) return;
        try {
            players.save(playersFile);
            history.save(historyFile);
            dirty = false;
        } catch (IOException ex) {
            getLogger().severe("Could not save data: " + ex.getMessage());
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (p.getAddress() == null) return;
        String ip = p.getAddress().getAddress().getHostAddress();
        long now = System.currentTimeMillis();
        String base = "players." + key(p.getName());
        players.set(base + ".name", p.getName());
        if (!players.contains(base + ".first")) players.set(base + ".first", now);
        players.set(base + ".last", now);

        List<String> ips = new ArrayList<>(players.getStringList(base + ".ips"));
        boolean found = false;
        for (int i = 0; i < ips.size(); i++) {
            String[] part = ips.get(i).split(";");
            if (part.length == 3 && part[0].equals(ip)) {
                ips.set(i, part[0] + ";" + part[1] + ";" + now);
                found = true;
                break;
            }
        }
        if (!found) ips.add(ip + ";" + now + ";" + now);
        players.set(base + ".ips", ips);
        dirty = true;
    }

    private List<String[]> ipsOfKey(String k) {
        List<String[]> out = new ArrayList<>();
        for (String raw : players.getStringList("players." + k + ".ips")) {
            String[] p = raw.split(";");
            if (p.length == 3) out.add(p);
        }
        return out;
    }

    private void logHistory(String name, String action, String staff, String reason) {
        String k = "history." + key(name);
        List<String> list = new ArrayList<>(history.getStringList(k));
        list.add((System.currentTimeMillis() / 1000) + "|" + action + "|" + staff.replace('|', '/') + "|"
                + reason.replace('|', '/'));
        history.set(k, list);
        dirty = true;
    }

    /** Calls AuthMe's API by reflection so the plugin still loads if its API changes. */
    private Object authme(String method, String arg) {
        try {
            Class<?> c = Class.forName("fr.xephi.authme.api.v3.AuthMeApi");
            Object api = c.getMethod("getInstance").invoke(null);
            return c.getMethod(method, String.class).invoke(api, arg);
        } catch (Throwable t) {
            return null;
        }
    }

    private static long toSeconds(Object o) {
        if (o instanceof Instant i) return i.getEpochSecond();
        if (o instanceof Number n) {
            long v = n.longValue();
            return v > 100000000000L ? v / 1000 : v;
        }
        return 0;
    }

    private String status(String n) {
        StringBuilder b = new StringBuilder();
        if (Bukkit.getPlayerExact(n) != null) b.append(" (online)");
        OfflinePlayer op = Bukkit.getOfflinePlayerIfCached(n);
        if (op != null && op.isBanned()) b.append(" (banned)");
        return b.toString();
    }

    private static String cap(String s) {
        return s.length() > 1900 ? s.substring(0, 1900) + "\n..." : s;
    }

    private String tempPassword() {
        StringBuilder sb = new StringBuilder(12);
        for (int i = 0; i < 12; i++) sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        return sb.toString();
    }

    private String cleanReason(String s) {
        String r = s.replaceAll("[\\r\\n]+", " ").trim();
        if (r.length() > 100) r = r.substring(0, 100);
        return r.isEmpty() ? "Banned by staff" : r;
    }

    private static boolean hasAny(Member m, Set<Long> ids) {
        return m != null && m.getRoles().stream().anyMatch(r -> ids.contains(r.getIdLong()));
    }

    /* -------------------------------------------------------- discord */

    private final class ResetListener extends ListenerAdapter {
        @Override
        public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
            String cmd = event.getName();
            if (!cmd.equals("resetpass") && !cmd.equals("ban") && !cmd.equals("unban")
                    && !cmd.equals("history") && !cmd.equals("alts") && !cmd.equals("ip")) return;

            Set<Long> need = (cmd.equals("ip") || cmd.equals("alts")) && !ipRoleIds.isEmpty() ? ipRoleIds : staffRoleIds;
            if (!hasAny(event.getMember(), need)) {
                event.reply("You are not allowed to use this.").setEphemeral(true).queue();
                return;
            }

            String name = event.getOption("player").getAsString().trim();
            if (!NAME.matcher(name).matches()) {
                event.reply("That is not a valid Minecraft username.").setEphemeral(true).queue();
                return;
            }

            String staff = event.getUser().getName();
            switch (cmd) {
                case "resetpass" -> resetPass(event, name, staff);
                case "ban" -> ban(event, name, staff);
                case "unban" -> unban(event, name, staff);
                case "history" -> historyCmd(event, name, staff);
                case "alts" -> altsCmd(event, name, staff);
                case "ip" -> ipCmd(event, name, staff);
                default -> { }
            }
        }

        private void resetPass(SlashCommandInteractionEvent event, String name, String staff) {
            event.deferReply(true).queue();
            getServer().getScheduler().runTask(TicketReset.this, () -> {
                try {
                    Class<?> c = Class.forName("fr.xephi.authme.api.v3.AuthMeApi");
                    Object api = c.getMethod("getInstance").invoke(null);
                    Object reg = c.getMethod("isRegistered", String.class).invoke(api, name);
                    if (!Boolean.TRUE.equals(reg)) {
                        event.getHook().sendMessage("**" + name + "** is not registered.").queue();
                        return;
                    }
                    String temp = tempPassword();
                    c.getMethod("changePassword", String.class, String.class).invoke(api, name, temp);
                    getLogger().info(staff + " reset the password of " + name + " from Discord.");
                    event.getHook().sendMessage("Temporary password for **" + name + "**: `" + temp
                            + "`\nTell them to log in and run `/changepassword <old> <new>`.").queue();
                } catch (Exception ex) {
                    getLogger().severe("Password reset failed: " + ex);
                    event.getHook().sendMessage("Could not reset the password. Check the server console.").queue();
                }
            });
        }

        private void ban(SlashCommandInteractionEvent event, String name, String staff) {
            OptionMapping r = event.getOption("reason");
            String reason = r == null ? "Banned by staff" : cleanReason(r.getAsString());
            event.deferReply(false).queue();
            getServer().getScheduler().runTask(TicketReset.this, () -> {
                if (Bukkit.getOfflinePlayer(name).isOp()) {
                    event.getHook().sendMessage("**" + name + "** is an operator and can't be banned from Discord.").queue();
                    return;
                }
                boolean ok = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "ban " + name + " " + reason);
                if (ok) logHistory(name, "BAN", staff, reason);
                getLogger().info(staff + " banned " + name + " from Discord. Reason: " + reason);
                event.getHook().sendMessage(ok
                        ? "**" + name + "** was banned. Reason: " + reason
                        : "Nothing changed. **" + name + "** may already be banned.").queue();
            });
        }

        private void unban(SlashCommandInteractionEvent event, String name, String staff) {
            event.deferReply(false).queue();
            getServer().getScheduler().runTask(TicketReset.this, () -> {
                boolean ok = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "pardon " + name);
                if (ok) logHistory(name, "UNBAN", staff, "-");
                getLogger().info(staff + " unbanned " + name + " from Discord.");
                event.getHook().sendMessage(ok
                        ? "**" + name + "** was unbanned."
                        : "Nothing changed. **" + name + "** may not be banned.").queue();
            });
        }

        private void historyCmd(SlashCommandInteractionEvent event, String name, String staff) {
            event.deferReply(true).queue();
            getServer().getScheduler().runTask(TicketReset.this, () -> {
                OfflinePlayer op = Bukkit.getOfflinePlayerIfCached(name);
                String base = "players." + key(name);
                long first = players.getLong(base + ".first", 0) / 1000;
                long last = players.getLong(base + ".last", 0) / 1000;
                if (op != null) {
                    long f = op.getFirstPlayed() / 1000;
                    long l = op.getLastPlayed() / 1000;
                    if (f > 0 && (first == 0 || f < first)) first = f;
                    if (l > last) last = l;
                }
                Object reg = authme("isRegistered", name);
                long lastLogin = toSeconds(authme("getLastLoginTime", name));
                List<String> entries = history.getStringList("history." + key(name));

                if (first == 0 && last == 0 && entries.isEmpty() && !Boolean.TRUE.equals(reg)) {
                    event.getHook().sendMessage("No record of **" + name + "**. They may never have joined.").queue();
                    return;
                }
                StringBuilder sb = new StringBuilder("**History for " + name + "**" + status(name) + "\n");
                sb.append("- First joined: ").append(first > 0 ? "<t:" + first + ":f>" : "unknown").append("\n");
                sb.append("- Last seen: ").append(last > 0 ? "<t:" + last + ":R>" : "unknown").append("\n");
                sb.append("- Registered (AuthMe): ").append(Boolean.TRUE.equals(reg) ? "yes" : "no").append("\n");
                if (lastLogin > 0) sb.append("- Last login (AuthMe): <t:").append(lastLogin).append(":R>\n");
                sb.append("\n**Punishments (").append(entries.size()).append("):**\n");
                if (entries.isEmpty()) sb.append("None logged.\n");
                int shown = 0;
                for (int i = entries.size() - 1; i >= 0 && shown < 10; i--, shown++) {
                    String[] p = entries.get(i).split("\\|", 4);
                    if (p.length < 4) continue;
                    sb.append("- <t:").append(p[0]).append(":d> **").append(p[1]).append("** by ").append(p[2]);
                    if (!p[3].equals("-")) sb.append(": ").append(p[3]);
                    sb.append("\n");
                }
                sb.append("_Only bans/unbans done through this bot are logged._");
                getLogger().info(staff + " viewed the history of " + name + ".");
                event.getHook().sendMessage(cap(sb.toString())).queue();
            });
        }

        private void ipCmd(SlashCommandInteractionEvent event, String name, String staff) {
            event.deferReply(true).queue();
            getServer().getScheduler().runTask(TicketReset.this, () -> {
                List<String[]> list = ipsOfKey(key(name));
                Object am = authme("getLastIp", name);
                String amIp = am instanceof String s2 && !s2.isBlank() ? s2 : null;
                if (list.isEmpty() && amIp == null) {
                    event.getHook().sendMessage("No IP known for **" + name + "**. They may never have joined.").queue();
                    return;
                }
                StringBuilder sb = new StringBuilder("**IPs for " + name + "**" + status(name) + "\n");
                for (String[] p : list) {
                    sb.append("- `").append(p[0]).append("` last seen <t:")
                            .append(Long.parseLong(p[2]) / 1000).append(":R>\n");
                }
                if (amIp != null) sb.append("- AuthMe last IP: `").append(amIp).append("`\n");
                sb.append("_Private. Don't post IPs in public channels._");
                getLogger().info(staff + " looked up the IP of " + name + ".");
                event.getHook().sendMessage(cap(sb.toString())).queue();
            });
        }

        private void altsCmd(SlashCommandInteractionEvent event, String name, String staff) {
            event.deferReply(true).queue();
            getServer().getScheduler().runTask(TicketReset.this, () -> {
                Set<String> myIps = new LinkedHashSet<>();
                for (String[] p : ipsOfKey(key(name))) myIps.add(p[0]);
                Object am = authme("getLastIp", name);
                if (am instanceof String s2 && !s2.isBlank()) myIps.add(s2);
                if (myIps.isEmpty()) {
                    event.getHook().sendMessage("No IP known for **" + name + "**. They may never have joined.").queue();
                    return;
                }

                Map<String, Set<String>> found = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
                ConfigurationSection sec = players.getConfigurationSection("players");
                if (sec != null) {
                    for (String k : sec.getKeys(false)) {
                        String other = players.getString("players." + k + ".name", k);
                        if (other.equalsIgnoreCase(name)) continue;
                        for (String[] p : ipsOfKey(k)) {
                            if (myIps.contains(p[0])) found.computeIfAbsent(other, x -> new TreeSet<>()).add(p[0]);
                        }
                    }
                }
                for (String ip : myIps) {
                    Object r = authme("getNamesByIp", ip);
                    if (r instanceof List<?> l) {
                        for (Object o : l) {
                            String n = String.valueOf(o);
                            if (!n.equalsIgnoreCase(name)) found.computeIfAbsent(n, x -> new TreeSet<>()).add(ip);
                        }
                    }
                }

                if (found.isEmpty()) {
                    event.getHook().sendMessage("No other accounts share an IP with **" + name
                            + "**.\n_Tracking starts when this plugin was installed, plus AuthMe's data._").queue();
                    return;
                }
                StringBuilder sb = new StringBuilder("**Possible alts of " + name + "** (" + found.size() + ")\n");
                int shown = 0;
                for (Map.Entry<String, Set<String>> e : found.entrySet()) {
                    if (shown++ >= 25) {
                        sb.append("...and more\n");
                        break;
                    }
                    sb.append("- **").append(e.getKey()).append("**").append(status(e.getKey()))
                            .append(" - shared IP `").append(String.join("`, `", e.getValue())).append("`\n");
                }
                sb.append("_Same IP does not always mean same person (family, school, VPN)._");
                getLogger().info(staff + " looked up the alts of " + name + ".");
                event.getHook().sendMessage(cap(sb.toString())).queue();
            });
        }
    }
}
