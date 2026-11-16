package dev.ticketreset;

import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

public class DiscordListener extends ListenerAdapter {

    private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9_]{3,16}$");
    private static final Set<String> COMMANDS =
            Set.of("resetpass", "ban", "unban", "mute", "unmute", "history", "alts");

    private final TicketReset plugin;
    private final DataStore store;

    public DiscordListener(TicketReset plugin, DataStore store) {
        this.plugin = plugin;
        this.store = store;
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        final String cmd = event.getName();
        if (!COMMANDS.contains(cmd)) return;

        Member member = event.getMember();
        if (member == null || member.getRoles().stream().noneMatch(r -> plugin.isStaffRole(r.getIdLong()))) {
            event.reply("You are not allowed to use this.").setEphemeral(true).queue();
            return;
        }

        OptionMapping po = event.getOption("player");
        final String player = po == null ? "" : po.getAsString().trim();
        if (!NAME.matcher(player).matches()) {
            event.reply("That is not a valid Minecraft username.").setEphemeral(true).queue();
            return;
        }

        final String staff = event.getUser().getName();
        final String reason = clean(optString(event, "reason"), cmd.equals("ban") ? "Banned by staff" : "");

        long until = 0;
        if (cmd.equals("mute")) {
            String dur = optString(event, "duration");
            if (!dur.isBlank()) {
                long ms = TicketReset.parseDuration(dur);
                if (ms < 0) {
                    event.reply("Invalid duration. Use something like `30m`, `2h`, `7d` or `1w`.")
                            .setEphemeral(true).queue();
                    return;
                }
                until = System.currentTimeMillis() + ms;
            }
        }
        final long muteUntil = until;

        event.deferReply(true).queue();
        Bukkit.getScheduler().runTask(plugin, () -> {
            String out;
            try {
                out = switch (cmd) {
                    case "resetpass" -> resetPass(player, staff);
                    case "ban" -> ban(player, reason, staff);
                    case "unban" -> unban(player, staff);
                    case "mute" -> mute(player, reason, staff, muteUntil);
                    case "unmute" -> unmute(player, staff);
                    case "history" -> history(player);
                    default -> alts(player);
                };
            } catch (Exception e) {
                plugin.getLogger().severe(cmd + " failed for " + player + ": " + e);
                out = "Something went wrong. Check the server console.";
            }
            if (out.length() > 1900) out = out.substring(0, 1900) + "...";
            event.getHook().sendMessage(out).queue();
        });
    }

    private String resetPass(String player, String staff) {
        OfflinePlayer op = Bukkit.getOfflinePlayer(player);
        if (op.isOp()) return "Refusing to reset the password of an operator.";
        try {
            Class<?> api = Class.forName("fr.xephi.authme.api.v3.AuthMeApi");
            Object inst = api.getMethod("getInstance").invoke(null);
            Object registered = api.getMethod("isRegistered", String.class).invoke(inst, player);
            if (!Boolean.TRUE.equals(registered)) return "`" + player + "` is not registered.";

            String temp = plugin.randomPassword(12);
            api.getMethod("changePassword", String.class, String.class).invoke(inst, player, temp);
            store.addHistory(player, "PASSWORD_RESET", staff, "");
            plugin.getLogger().info(staff + " reset the password of " + player);
            return "Temporary password for `" + player + "`: `" + temp + "`";
        } catch (Exception e) {
            plugin.getLogger().severe("Password reset failed: " + e);
            return "Could not reset the password. Check the server console.";
        }
    }

    private String ban(String player, String reason, String staff) {
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "ban " + player + " " + reason);
        store.addHistory(player, "BAN", staff, reason);
        plugin.getLogger().info(staff + " banned " + player + ": " + reason);
        return "Banned `" + player + "`. Reason: " + reason;
    }

    private String unban(String player, String staff) {
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "pardon " + player);
        store.addHistory(player, "UNBAN", staff, "");
        plugin.getLogger().info(staff + " unbanned " + player);
        return "Unbanned `" + player + "`.";
    }

    private String mute(String player, String reason, String staff, long until) {
        store.mute(player, reason, staff, until);
        String dur = until > 0 ? "for " + TicketReset.formatDuration(until - System.currentTimeMillis()) : "permanently";
        store.addHistory(player, "MUTE", staff, (reason.isBlank() ? "" : reason + " ") + "(" + dur + ")");
        plugin.getLogger().info(staff + " muted " + player + " " + dur);

        Player online = Bukkit.getPlayerExact(player);
        DataStore.MuteEntry m = store.getMute(player);
        if (online != null && m != null) online.sendMessage(PlayerListener.mutedMessage(m));

        return "Muted `" + player + "` " + dur + (reason.isBlank() ? "." : ". Reason: " + reason);
    }

    private String unmute(String player, String staff) {
        if (!store.unmute(player)) return "`" + player + "` is not muted.";
        store.addHistory(player, "UNMUTE", staff, "");
        plugin.getLogger().info(staff + " unmuted " + player);
        Player online = Bukkit.getPlayerExact(player);
        if (online != null) online.sendMessage(ChatColor.GREEN + "You have been unmuted.");
        return "Unmuted `" + player + "`.";
    }

    private String history(String player) {
        StringBuilder sb = new StringBuilder("**History for `" + player + "`**" + status(player) + "\n");
        List<DataStore.HistoryEntry> entries = store.getHistory(player);
        if (entries.isEmpty()) return sb.append("No recorded history.").toString();

        int shown = 0;
        for (DataStore.HistoryEntry e : entries) {
            if (shown++ >= 10) break;
            String r = e.reason().length() > 80 ? e.reason().substring(0, 80) + "..." : e.reason();
            sb.append("<t:").append(e.time() / 1000).append(":f> **").append(e.type()).append("** by ")
              .append(e.actor());
            if (!r.isBlank()) sb.append(" - ").append(r);
            sb.append("\n");
        }
        if (entries.size() > 10) sb.append("(showing latest 10 of ").append(entries.size()).append(")");
        return sb.toString();
    }

    private String alts(String player) {
        if (!store.hasIpData(player)) {
            return "No IP data recorded for `" + player + "` yet. IPs are saved when a player joins.";
        }
        Set<String> alts = store.altsOf(player);
        StringBuilder sb = new StringBuilder("**Possible alts of `" + player + "`**" + status(player) + "\n");
        if (alts.isEmpty()) return sb.append("No other accounts share an IP with this player.").toString();

        for (String alt : alts) {
            OfflinePlayer op = Bukkit.getOfflinePlayer(alt);
            sb.append("- `").append(alt).append("`").append(status(alt));
            if (op.getLastPlayed() > 0) sb.append(" - last seen <t:").append(op.getLastPlayed() / 1000).append(":R>");
            sb.append("\n");
        }
        sb.append("Shared IPs can also be family, a school or a VPN, so treat this as a hint.");
        return sb.toString();
    }

    private String status(String name) {
        List<String> flags = new ArrayList<>();
        if (Bukkit.getOfflinePlayer(name).isBanned()) flags.add("BANNED");
        if (store.getMute(name) != null) flags.add("MUTED");
        return flags.isEmpty() ? "" : " [" + String.join(", ", flags) + "]";
    }

    private static String optString(SlashCommandInteractionEvent e, String name) {
        OptionMapping o = e.getOption(name);
        return o == null ? "" : o.getAsString();
    }

    private static String clean(String s, String fallback) {
        String t = s.replaceAll("[\\r\\n]+", " ").trim();
        if (t.isEmpty()) return fallback;
        return t.length() > 100 ? t.substring(0, 100) : t;
    }
}
