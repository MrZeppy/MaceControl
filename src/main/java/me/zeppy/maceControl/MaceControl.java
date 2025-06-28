package me.zeppy.maceControl;

import org.bukkit.*;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.*;

public final class MaceControl extends JavaPlugin implements Listener {
    private static MaceControl instance;
    private static MaceTracker tracker;
    private static MaceManager manager;
    private static MaceEventHandler eventHandler;
    private static MaceCommandHandler commandHandler;

    public static NamespacedKey MACE_ID_KEY;

    // Configuration constants
    private static final String DEFAULT_BYPASS_PERMISSION = "macecontrol.bypass";
    private static final String DEFAULT_ADMIN_PERMISSION = "macecontrol.admin";
    private static final int DEFAULT_MAX_MACE_COUNT = 5;
    private static final long DEFAULT_SAVE_INTERVAL_MINUTES = 5;
    private static final long DEFAULT_COMMAND_COOLDOWN_SECONDS = 5;

    public static MaceTracker getTracker() {
        return tracker;
    }

    public static MaceManager getManager() {
        return manager;
    }

    public static MaceEventHandler getEventHandler() {
        return eventHandler;
    }

    public static MaceCommandHandler getCommandHandler() {
        return commandHandler;
    }

    @Override
    public void onEnable() {
        instance = this;
        MACE_ID_KEY = new NamespacedKey(this, "mace_id");


        saveDefaultConfig();
        validateConfig();

        try {
            // Initialize
            tracker = new MaceTracker(this);
            manager = new MaceManager(this);
            eventHandler = new MaceEventHandler(this);
            commandHandler = new MaceCommandHandler(this);

            getServer().getPluginManager().registerEvents(eventHandler, this);

            // Register commands
            if (getCommand("mace") != null) {
                getCommand("mace").setExecutor(commandHandler);
            } else {
                getLogger().warning("Command 'mace' not found in plugin.yml");
            }

            if (getCommand("droppedmace") != null) {
                getCommand("droppedmace").setExecutor(commandHandler);
            } else {
                getLogger().warning("Command 'droppedmace' not found in plugin.yml");
            }

            if (getCommand("macereload") != null) {
                getCommand("macereload").setExecutor(this);
            } else {
                getLogger().warning("Command 'macereload' not found in plugin.yml");
            }


            manager.loadMaceData();
            tracker.refreshMaceTracking();

            getLogger().info("MaceControl Enabled! Total maces: "
                    + tracker.getMaceCount()
                    + ", tracked holders: "
                    + tracker.getCurrentMaceHolders().size());

            // Set up auto-save with configurable interval
            long saveIntervalMinutes = getConfig().getLong("auto-save-interval-minutes", DEFAULT_SAVE_INTERVAL_MINUTES);
            long saveIntervalTicks = saveIntervalMinutes * 60 * 20;

            getServer().getScheduler().runTaskTimer(this, () -> {
                try {
                    manager.saveMaceData();
                    getLogger().info("Autosaved mace data.");
                } catch (Exception e) {
                    getLogger().severe("Failed to autosave mace data: " + e.getMessage());
                }
            }, saveIntervalTicks, saveIntervalTicks);

        } catch (Exception e) {
            getLogger().severe("Failed to enable MaceControl: " + e.getMessage());
            e.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        try {
            if (manager != null) {
                manager.saveMaceData();
            }
            getLogger().info("MaceControl Disabled!");
        } catch (Exception e) {
            getLogger().severe("Error during plugin disable: " + e.getMessage());
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("macereload")) {
            if (!sender.hasPermission(getConfig().getString("admin-permission", DEFAULT_ADMIN_PERMISSION))) {
                sender.sendMessage(ChatColor.RED + "You don't have permission to use this command.");
                return true;
            }

            try {
                reloadConfig();
                manager.reloadConfig();
                sender.sendMessage(ChatColor.GREEN + "MaceControl configuration reloaded!");
                getLogger().info("Configuration reloaded by " + sender.getName());
            } catch (Exception e) {
                sender.sendMessage(ChatColor.RED + "Error reloading configuration: " + e.getMessage());
                getLogger().severe("Error reloading configuration: " + e.getMessage());
            }
            return true;
        }
        return false;
    }

    private void validateConfig() {
        boolean configChanged = false;

        // Core permissions
        if (!getConfig().contains("bypass-permission")) {
            getConfig().set("bypass-permission", DEFAULT_BYPASS_PERMISSION);
            configChanged = true;
        }
        if (!getConfig().contains("admin-permission")) {
            getConfig().set("admin-permission", DEFAULT_ADMIN_PERMISSION);
            configChanged = true;
        }

        // Core settings
        if (!getConfig().contains("max-mace-count")) {
            getConfig().set("max-mace-count", DEFAULT_MAX_MACE_COUNT);
            configChanged = true;
        }
        if (!getConfig().contains("auto-save-interval-minutes")) {
            getConfig().set("auto-save-interval-minutes", DEFAULT_SAVE_INTERVAL_MINUTES);
            configChanged = true;
        }
        if (!getConfig().contains("mace-command-cooldown")) {
            getConfig().set("mace-command-cooldown", DEFAULT_COMMAND_COOLDOWN_SECONDS);
            configChanged = true;
        }

        // World restrictions
        if (!getConfig().contains("worlds.allowed-craft-worlds")) {
            getConfig().set("worlds.allowed-craft-worlds", new ArrayList<String>());
            configChanged = true;
        }
        if (!getConfig().contains("worlds.restricted-worlds")) {
            getConfig().set("worlds.restricted-worlds", new ArrayList<String>());
            configChanged = true;
        }

        // Restriction settings
        if (!getConfig().contains("restrictions.block-containers")) {
            getConfig().set("restrictions.block-containers", true);
            configChanged = true;
        }
        if (!getConfig().contains("restrictions.allow-ender-chest")) {
            getConfig().set("restrictions.allow-ender-chest", false);
            configChanged = true;
        }
        if (!getConfig().contains("restrictions.allow-shulker-boxes")) {
            getConfig().set("restrictions.allow-shulker-boxes", false);
            configChanged = true;
        }
        if (!getConfig().contains("restrictions.block-item-frames")) {
            getConfig().set("restrictions.block-item-frames", true);
            configChanged = true;
        }
        if (!getConfig().contains("restrictions.block-armor-stands")) {
            getConfig().set("restrictions.block-armor-stands", true);
            configChanged = true;
        }
        if (!getConfig().contains("restrictions.block-heavy-core-autocrafter")) {
            getConfig().set("restrictions.block-heavy-core-autocrafter", true);
            configChanged = true;
        }


        // Message settings
        if (!getConfig().contains("messages.broadcast-destruction")) {
            getConfig().set("messages.broadcast-destruction", true);
            configChanged = true;
        }
        if (!getConfig().contains("messages.broadcast-craft")) {
            getConfig().set("messages.broadcast-craft", true);
            configChanged = true;
        }
        if (!getConfig().contains("restrictions.block-flower-pots")) {
            getConfig().set("restrictions.block-flower-pots", true);
            configChanged = true;
        }

        if (configChanged) {
            saveConfig();
            getLogger().info("Configuration validated and updated with missing values.");
        } else {
            getLogger().info("Configuration validation complete - no changes needed.");
        }
    }

    public static MaceControl getInstance() {
        return instance;
    }
}