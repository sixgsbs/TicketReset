package dev.ticketreset;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import org.bukkit.plugin.java.JavaPlugin;

import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

public final class TicketReset extends JavaPlugin {

    private static final String ALPHABET = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9_]{3,16}$");

    private final SecureRandom random = new SecureRandom();
    private JDA jda;
    private final Set<Long> staffRoleIds = new HashSet<>();

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

        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            try {
                jda = JDABuilder.createLight(token).addEventListeners(new ResetListener()).build().awaitReady();
                Guild guild = jda.getGuildById(guildId.trim());
                if (guild == null) {
                    getLogger().severe("Bot is not in the guild " + guildId + ". Invite it first.");
                    return;
                }
                guild.upsertCommand(Commands.slash("resetpass", "Give a player a temporary password")
                        .addOption(OptionType.STRING, "player", "Minecraft username", true)
                        .setGuildOnly(true)).queue();
                getLogger().info("Discord bot ready.");
            } catch (Exception e) {
                getLogger().severe("Discord bot failed to start: " + e.getMessage());
            }
        });
    }

    @Override
    public void onDisable() {
        if (jda != null) jda.shutdown();
    }

    private String tempPassword() {
        StringBuilder sb = new StringBuilder(12);
        for (int i = 0; i < 12; i++) sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        return sb.toString();
    }

    private final class ResetListener extends ListenerAdapter {
        @Override
        public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
            if (!event.getName().equals("resetpass")) return;

            Member member = event.getMember();
            if (member == null || member.getRoles().stream().noneMatch(r -> staffRoleIds.contains(r.getIdLong()))) {
                event.reply("You are not allowed to use this.").setEphemeral(true).queue();
                return;
            }

            String name = event.getOption("player").getAsString().trim();
            if (!NAME.matcher(name).matches()) {
                event.reply("That is not a valid Minecraft username.").setEphemeral(true).queue();
                return;
            }

            event.deferReply(true).queue();
            String staffTag = event.getUser().getName();

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
                    getLogger().info(staffTag + " reset the password of " + name + " from Discord.");
                    event.getHook().sendMessage("Temporary password for **" + name + "**: `" + temp
                            + "`\nTell them to log in and run `/changepassword <old> <new>`.").queue();
                } catch (Exception ex) {
                    getLogger().severe("Password reset failed: " + ex);
                    event.getHook().sendMessage("Could not reset the password. Check the server console.").queue();
                }
            });
        }
    }
}

