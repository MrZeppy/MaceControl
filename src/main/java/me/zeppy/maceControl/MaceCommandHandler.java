package me.zeppy.maceControl;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class MaceCommandHandler implements CommandExecutor {
    private final MaceControl plugin;
    private final Map<UUID, Long> maceCommandCooldowns = new HashMap<>();
    private long MACE_COMMAND_COOLDOWN;

    public Map<UUID, Long> getMaceCommandCooldowns() {
        return maceCommandCooldowns;
    }

    public long getMaceCommandCooldown() {
        return MACE_COMMAND_COOLDOWN;
    }

    public MaceControl getPlugin() {
        return plugin;
    }

    public MaceCommandHandler(MaceControl plugin) {
        this.plugin = plugin;
        this.MACE_COMMAND_COOLDOWN = plugin.getConfig().getLong("mace-command-cooldown", 5);
    }

    private boolean hasPermission(CommandSender sender, String permission) {
        return sender.hasPermission(permission);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // Admin-only command: /droppedmace
        if (command.getName().equalsIgnoreCase("droppedmace")) {
            if (!hasPermission(sender, plugin.getConfig().getString("admin-permission", "macecontrol.admin"))) {
                sender.sendMessage(ChatColor.RED + "You don't have permission to use this command.");
                return true;
            }

            sender.sendMessage(ChatColor.AQUA + "[MaceAdmin] Currently dropped (temp) maces:");

            // Use the new pending drops method
            Map<UUID, MaceTracker.MaceDropInfo> pendingDrops = plugin.getTracker().getPendingDropsNew();

            if (pendingDrops.isEmpty()) {
                sender.sendMessage(ChatColor.GRAY + "- None");
            } else {
                for (Map.Entry<UUID, MaceTracker.MaceDropInfo> entry : pendingDrops.entrySet()) {
                    UUID itemUUID = entry.getKey();
                    MaceTracker.MaceDropInfo dropInfo = entry.getValue();
                    String playerName = Bukkit.getOfflinePlayer(dropInfo.originalHolder).getName();
                    long dropTime = dropInfo.dropTime;
                    long timeElapsed = (System.currentTimeMillis() - dropTime) / 1000; // seconds

                    sender.sendMessage(ChatColor.GRAY + "- Item: " + itemUUID +
                            " | Dropped by: " + playerName +
                            " | Mace ID: " + dropInfo.maceId +
                            " | Time elapsed: " + timeElapsed + "s");
                }
            }
            return true;
        }

        // Player-accessible command: /mace
        if (command.getName().equalsIgnoreCase("mace")) {
            if (sender instanceof Player player) {
                UUID uuid = player.getUniqueId();
                long currentTime = System.currentTimeMillis();
                long cooldownMillis = getMaceCommandCooldownMillis();

                if (maceCommandCooldowns.containsKey(uuid)) {
                    long lastUsed = maceCommandCooldowns.get(uuid);
                    if ((currentTime - lastUsed) < cooldownMillis) {
                        long secondsLeft = (cooldownMillis - (currentTime - lastUsed)) / 1000;
                        player.sendMessage(ChatColor.RED + "Please wait " + secondsLeft + "s before using this command again.");
                        return true;
                    }
                }

                maceCommandCooldowns.put(uuid, currentTime);
            }

            plugin.getTracker().refreshMaceTracking();

            // Use thread-safe copy of holders
            Map<UUID, Integer> currentHolders = plugin.getTracker().getCurrentHoldersCopy();

            if (currentHolders.isEmpty()) {
                sender.sendMessage(ChatColor.RED + "The mace is not currently held by anyone.");
            } else {
                StringBuilder holders = new StringBuilder();
                for (Map.Entry<UUID, Integer> entry : currentHolders.entrySet()) {
                    UUID uuid = entry.getKey();
                    int count = entry.getValue();
                    OfflinePlayer player = Bukkit.getOfflinePlayer(uuid);
                    String name = player.getName() != null ? player.getName() : "Unknown Player";
                    holders.append(name).append(" (").append(count).append("), ");
                }

                if (holders.length() > 2) {
                    holders.setLength(holders.length() - 2);
                }

                sender.sendMessage(ChatColor.GREEN + "The mace is currently held by: " + holders);
            }

            return true;
        }

        return false;
    }

    private long getMaceCommandCooldownMillis() {
        return MACE_COMMAND_COOLDOWN * 1000;
    }
}