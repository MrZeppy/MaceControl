package me.zeppy.maceControl;

import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class MaceManager {

    private final MaceControl plugin;
    private int MAX_MACE_COUNT;

    public int getMaxMaceCount() {
        return MAX_MACE_COUNT;
    }

    public MaceControl getPlugin() {
        return plugin;
    }

    public MaceManager(MaceControl plugin) {
        this.plugin = plugin;
        this.MAX_MACE_COUNT = plugin.getConfig().getInt("max-mace-count", 5);
    }

    public void reloadConfig() {
        plugin.reloadConfig();
        this.MAX_MACE_COUNT = plugin.getConfig().getInt("max-mace-count", 5);
        plugin.getLogger().info("Configuration reloaded. Max mace count: " + MAX_MACE_COUNT);
    }

    void loadMaceData() {
        File dataFolder = plugin.getDataFolder();
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }

        File dataFile = new File(plugin.getDataFolder(), "maceData.yml");

        if (!dataFile.exists()) {
            plugin.getLogger().info("No maceData.yml found, starting fresh.");
            return;
        }

        YamlConfiguration config = YamlConfiguration.loadConfiguration(dataFile);

        // Load mace count
        MaceControl.getTracker().setMaceCount(config.getInt("maceCount", 0));
        plugin.getLogger().info("Loaded maceCount: " + MaceControl.getTracker().getMaceCount());

        // Load maceIdToHolder map
        Map<String, Object> idToHolderRaw = config.getConfigurationSection("maceIdToHolder") != null
                ? config.getConfigurationSection("maceIdToHolder").getValues(false)
                : new HashMap<>();

        // Clear existing data before loading
        MaceControl.getTracker().getMaceIdToHolder().clear();

        for (Map.Entry<String, Object> entry : idToHolderRaw.entrySet()) {
            try {
                MaceControl.getTracker().getMaceIdToHolder().put(entry.getKey(), UUID.fromString((String) entry.getValue()));
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Invalid UUID for maceId: " + entry.getKey());
            }
        }
        plugin.getLogger().info("Loaded " + MaceControl.getTracker().getMaceIdToHolder().size() + " mace ID mappings");

        // Load currentMaceHolders map
        Map<String, Object> holderMapRaw = config.getConfigurationSection("currentMaceHolders") != null
                ? config.getConfigurationSection("currentMaceHolders").getValues(false)
                : new HashMap<>();

        // Clear existing data before loading
        MaceControl.getTracker().getCurrentMaceHolders().clear();

        for (Map.Entry<String, Object> entry : holderMapRaw.entrySet()) {
            try {
                UUID uuid = UUID.fromString(entry.getKey());
                int count = entry.getValue() instanceof Integer ? (int) entry.getValue() : 0;
                MaceControl.getTracker().getCurrentMaceHolders().put(uuid, count);
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to parse mace holder entry: " + entry + ", Error: " + e.getMessage());
            }
        }
        plugin.getLogger().info("Loaded " + MaceControl.getTracker().getCurrentMaceHolders().size() + " mace holders");

        plugin.getLogger().info("Mace data loaded successfully.");
    }

    void saveMaceData() {
        File dataFolder = plugin.getDataFolder();
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }

        File dataFile = new File(plugin.getDataFolder(), "maceData.yml");
        YamlConfiguration config = new YamlConfiguration();

        // Save mace count
        config.set("maceCount", MaceControl.getTracker().getMaceCount());

        // Save maceIdToHolder
        Map<String, String> idToHolderStr = new HashMap<>();
        for (Map.Entry<String, UUID> entry : MaceControl.getTracker().getMaceIdToHolder().entrySet()) {
            idToHolderStr.put(entry.getKey(), entry.getValue().toString());
        }
        config.createSection("maceIdToHolder", idToHolderStr);

        // Save currentMaceHolders
        Map<String, Integer> holderCountsStr = new HashMap<>();
        for (Map.Entry<UUID, Integer> entry : MaceControl.getTracker().getCurrentMaceHolders().entrySet()) {
            holderCountsStr.put(entry.getKey().toString(), entry.getValue());
        }
        config.createSection("currentMaceHolders", holderCountsStr);

        try {
            config.save(dataFile);
            plugin.getLogger().info("Mace data saved. Current holders: " + MaceControl.getTracker().getCurrentMaceHolders().size());
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to save mace data: " + e.getMessage());
        }
    }

    public ItemStack tagMace(ItemStack mace, UUID creatorUUID) {
        if (mace == null || mace.getType() == Material.AIR) return mace;

        ItemMeta meta = mace.getItemMeta();
        if (meta == null) return mace;

        PersistentDataContainer container = meta.getPersistentDataContainer();

        if (!container.has(MaceControl.MACE_ID_KEY, PersistentDataType.STRING)) {
            String uniqueId = UUID.randomUUID().toString();
            container.set(MaceControl.MACE_ID_KEY, PersistentDataType.STRING, uniqueId);
            mace.setItemMeta(meta);

            MaceControl.getTracker().getMaceIdToHolder().put(uniqueId, creatorUUID);
        }

        return mace;
    }

    public static String getMaceID(ItemStack item) {
        if (item == null || item.getType() != Material.MACE) return null;

        ItemMeta meta = item.getItemMeta();
        if (meta == null) return null;

        PersistentDataContainer container = meta.getPersistentDataContainer();
        if (!container.has(MaceControl.MACE_ID_KEY, PersistentDataType.STRING)) return null;

        return container.get(MaceControl.MACE_ID_KEY, PersistentDataType.STRING);
    }

    // Replace this with your actual check for mace uniqueness/tag
    public boolean isUniqueMace(ItemStack item) {
        if (item == null) {
            return false;
        }
        if (item.getType() != Material.MACE) {
            return false;
        }

        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return false;
        }

        PersistentDataContainer container = meta.getPersistentDataContainer();
        boolean hasKey = container.has(MaceControl.MACE_ID_KEY, PersistentDataType.STRING);
        return hasKey;
    }
}