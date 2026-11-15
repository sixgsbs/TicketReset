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
        if (jda != null) jda.shutdown();
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

    private final class ResetListener extends ListenerAdapter {
        @Override
        public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
            String cmd = event.getName();
            if (!cmd.equals("resetpass") && !cmd.equals("ban") && !cmd.equals("unban")) return;

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

            String staff = event.getUser().getName();
            switch (cmd) {
                case "resetpass" -> resetPass(event, name, staff);
                case "ban" -> ban(event, name, staff);
                case "unban" -> unban(event, name, staff);
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
                getLogger().info(staff + " unbanned " + name + " from Discord.");
                event.getHook().sendMessage(ok
                        ? "**" + name + "** was unbanned."
                        : "Nothing changed. **" + name + "** may not be banned.").queue();
            });
        }
    }
}
