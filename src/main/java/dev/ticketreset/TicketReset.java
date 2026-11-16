package dev.ticketreset;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import org.bukkit.plugin.java.JavaPlugin;

import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Set;

public class TicketReset extends JavaPlugin {

    private static final String ALPHABET = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private final SecureRandom random = new SecureRandom();
    private final Set<Long> staffRoleIds = new HashSet<>();
    private JDA jda;
    private DataStore store;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        String token = getConfig().getString("discord.token", "");
        String guildId = getConfig().getString("discord.guild-id", "");

        try {
            for (String id : getConfig().getStringList("discord.staff-role-ids")) {
                staffRoleIds.add(Long.parseLong(id.trim()));
            }
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
        if (token == null || token.isBlank() || token.startsWith("PUT_")) {
            getLogger().severe("Set discord.token in config.yml. Disabling.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        store = new DataStore(this);
        store.load();
        getServer().getPluginManager().registerEvents(new PlayerListener(this, store), this);

        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            try {
                jda = JDABuilder.createLight(token)
                        .addEventListeners(new DiscordListener(this, store))
                        .build()
                        .awaitReady();

                Guild guild = jda.getGuildById(guildId);
                if (guild == null) {
                    getLogger().severe("Bot is not in guild " + guildId + " (check discord.guild-id).");
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
                        Commands.slash("mute", "Mute a player in Minecraft chat")
                                .addOption(OptionType.STRING, "player", "Minecraft username", true)
                                .addOption(OptionType.STRING, "reason", "Reason for the mute", false)
                                .addOption(OptionType.STRING, "duration", "e.g. 30m, 2h, 7d (empty = permanent)", false)
                                .setGuildOnly(true),
                        Commands.slash("unmute", "Unmute a player")
                                .addOption(OptionType.STRING, "player", "Minecraft username", true)
                                .setGuildOnly(true),
                        Commands.slash("history", "Show a player's punishment history")
                                .addOption(OptionType.STRING, "player", "Minecraft username", true)
                                .setGuildOnly(true),
                        Commands.slash("alts", "Show accounts that share an IP with a player")
                                .addOption(OptionType.STRING, "player", "Minecraft username", true)
                                .setGuildOnly(true)
                ).queue();
                getLogger().info("Discord bot ready.");
            } catch (Exception e) {
                getLogger().severe("Could not start the Discord bot: " + e.getMessage());
            }
        });
    }

    @Override
    public void onDisable() {
        if (jda != null) jda.shutdown();
        if (store != null) store.saveNow();
    }

    public boolean isStaffRole(long roleId) {
        return staffRoleIds.contains(roleId);
    }

    public String randomPassword(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        return sb.toString();
    }

    public static String formatDuration(long ms) {
        long s = Math.max(1, ms / 1000);
        long d = s / 86400; s %= 86400;
        long h = s / 3600; s %= 3600;
        long m = s / 60; s %= 60;
        StringBuilder sb = new StringBuilder();
        if (d > 0) sb.append(d).append("d ");
        if (h > 0) sb.append(h).append("h ");
        if (m > 0) sb.append(m).append("m ");
        if (sb.isEmpty()) sb.append(s).append("s");
        return sb.toString().trim();
    }

    public static long parseDuration(String text) {
        var m = java.util.regex.Pattern.compile("^(\\d{1,6})([smhdw])$").matcher(text.trim().toLowerCase());
        if (!m.matches()) return -1;
        long n = Long.parseLong(m.group(1));
        return switch (m.group(2)) {
            case "s" -> n * 1000L;
            case "m" -> n * 60_000L;
            case "h" -> n * 3_600_000L;
            case "d" -> n * 86_400_000L;
            default -> n * 604_800_000L;
        };
    }
}
