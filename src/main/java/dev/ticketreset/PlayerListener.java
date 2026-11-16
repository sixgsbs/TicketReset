package dev.ticketreset;

import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Locale;

public class PlayerListener implements Listener {

    private final TicketReset plugin;
    private final DataStore store;

    public PlayerListener(TicketReset plugin, DataStore store) {
        this.plugin = plugin;
        this.store = store;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        InetSocketAddress addr = p.getAddress();
        if (addr != null && addr.getAddress() != null) {
            store.recordIp(p.getName(), addr.getAddress().getHostAddress());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent e) {
        DataStore.MuteEntry m = store.getMute(e.getPlayer().getName());
        if (m == null) return;
        e.setCancelled(true);
        e.getPlayer().sendMessage(mutedMessage(m));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        DataStore.MuteEntry m = store.getMute(e.getPlayer().getName());
        if (m == null) return;

        String label = e.getMessage().substring(1).split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        int colon = label.indexOf(':');
        if (colon >= 0) label = label.substring(colon + 1);

        List<String> blocked = plugin.getConfig().getStringList("muted-blocked-commands");
        final String finalLabel = label;
        if (blocked.stream().anyMatch(b -> b.equalsIgnoreCase(finalLabel))) {
            e.setCancelled(true);
            e.getPlayer().sendMessage(mutedMessage(m));
        }
    }

    public static String mutedMessage(DataStore.MuteEntry m) {
        String msg = ChatColor.RED + "You are muted";
        if (m.until() > 0) {
            msg += " for another " + TicketReset.formatDuration(m.until() - System.currentTimeMillis());
        }
        msg += ".";
        if (m.reason() != null && !m.reason().isBlank()) msg += " Reason: " + m.reason();
        return msg;
    }
}
