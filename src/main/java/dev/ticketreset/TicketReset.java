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
                                .addOption(OptionType.STRING, "player",
