package me.zeppy.maceControl;

import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class MaceTracker {
    private final MaceControl plugin;
    private final AtomicInteger maceCount = new AtomicInteger(0);

    // Thread-safe collections
    private final ConcurrentHashMap<String, UUID> maceIdToHolder = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Integer> currentMaceHolders = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, MaceDropInfo> pendingDrops = new ConcurrentHashMap<>();

    // Lock for complex operations that need atomicity
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    // Track mace drop information
    public static class MaceDropInfo {
        public final UUID originalHolder;
        public final String maceId;
        public final long dropTime;

        public MaceDropInfo(UUID originalHolder, String maceId) {
            this.originalHolder = originalHolder;
            this.maceId = maceId;
            this.dropTime = System.currentTimeMillis();
        }
    }

    public int getMaceCount() {
        return maceCount.get();
    }

    public void setMaceCount(int count) {
        maceCount.set(Math.max(0, count));
    }

    public Map<String, UUID> getMaceIdToHolder() {
        return maceIdToHolder;
    }

    public Map<UUID, Integer> getCurrentMaceHolders() {
        return currentMaceHolders;
    }

    public Map<UUID, UUID> getPendingDrops() {
        // Convert to old format for compatibility
        Map<UUID, UUID> oldFormat = new HashMap<>();
        for (Map.Entry<UUID, MaceDropInfo> entry : pendingDrops.entrySet()) {
            oldFormat.put(entry.getKey(), entry.getValue().originalHolder);
        }
        return oldFormat;
    }

    public Map<UUID, MaceDropInfo> getPendingDropsNew() {
        return pendingDrops;
    }

    public MaceControl getPlugin() {
        return plugin;
    }

    public MaceTracker(MaceControl plugin) {
        this.plugin = plugin;
    }

    void refreshMaceTracking() {
        lock.writeLock().lock();
        try {
            plugin.getLogger().info("Starting mace tracking refresh...");

            // Clear current tracking data
            maceCount.set(0);
            currentMaceHolders.clear();
            Set<String> foundMaceIds = new HashSet<>();
            Set<UUID> validDrops = new HashSet<>();

            // Step 1: Scan online players for maces in inventory
            for (Player player : plugin.getServer().getOnlinePlayers()) {
                int playerMaceCount = scanPlayerInventory(player, foundMaceIds);
                if (playerMaceCount > 0) {
                    currentMaceHolders.put(player.getUniqueId(), playerMaceCount);
                    maceCount.addAndGet(playerMaceCount);
                }
            }

            // Step 2: Scan for dropped maces in loaded chunks (with performance optimization)
            int droppedMaceCount = scanDroppedMaces(foundMaceIds, validDrops);
            maceCount.addAndGet(droppedMaceCount);

            // Step 3: Clean up stale mappings
            cleanupStaleMappings(foundMaceIds, validDrops);

            plugin.getLogger().info("Mace tracking refresh complete. Total maces: " + maceCount.get() +
                    ", Active holders: " + currentMaceHolders.size() +
                    ", Dropped maces: " + validDrops.size());

        } catch (Exception e) {
            plugin.getLogger().severe("Error during mace tracking refresh: " + e.getMessage());
            e.printStackTrace();
        } finally {
            lock.writeLock().unlock();
        }
    }

    private int scanPlayerInventory(Player player, Set<String> foundMaceIds) {
        int playerMaceCount = 0;

        try {
            // Check main inventory
            ItemStack[] contents = player.getInventory().getContents();
            if (contents != null) {
                for (ItemStack item : contents) {
                    if (item != null && MaceControl.getManager().isUniqueMace(item)) {
                        playerMaceCount++;
                        String maceId = MaceControl.getManager().getMaceID(item);
                        if (maceId != null) {
                            foundMaceIds.add(maceId);
                            maceIdToHolder.put(maceId, player.getUniqueId());
                        }
                    }
                }
            }

            // Check off-hand
            ItemStack offHandItem = player.getInventory().getItemInOffHand();
            if (offHandItem != null && MaceControl.getManager().isUniqueMace(offHandItem)) {
                playerMaceCount++;
                String maceId = MaceControl.getManager().getMaceID(offHandItem);
                if (maceId != null) {
                    foundMaceIds.add(maceId);
                    maceIdToHolder.put(maceId, player.getUniqueId());
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Error scanning inventory for player " + player.getName() + ": " + e.getMessage());
        }

        return playerMaceCount;
    }

    private int scanDroppedMaces(Set<String> foundMaceIds, Set<UUID> validDrops) {
        int droppedMaceCount = 0;

        try {
            // Only scan a limited number of worlds/chunks to prevent performance issues
            int maxWorldsToScan = plugin.getConfig().getInt("performance.max-worlds-to-scan", 3);
            int worldsScanned = 0;

            for (World world : plugin.getServer().getWorlds()) {
                if (worldsScanned >= maxWorldsToScan) break;
                worldsScanned++;

                Chunk[] loadedChunks = world.getLoadedChunks();
                int maxChunksPerWorld = plugin.getConfig().getInt("performance.max-chunks-per-world", 100);
                int chunksToScan = Math.min(loadedChunks.length, maxChunksPerWorld);

                for (int i = 0; i < chunksToScan; i++) {
                    Chunk chunk = loadedChunks[i];
                    try {
                        for (Entity entity : chunk.getEntities()) {
                            if (entity instanceof Item droppedItem) {
                                ItemStack itemStack = droppedItem.getItemStack();
                                if (itemStack != null && MaceControl.getManager().isUniqueMace(itemStack)) {
                                    droppedMaceCount++;
                                    String maceId = MaceControl.getManager().getMaceID(itemStack);
                                    if (maceId != null) {
                                        foundMaceIds.add(maceId);
                                        validDrops.add(entity.getUniqueId());
                                    }
                                }
                            }
                        }
                    } catch (Exception e) {
                        plugin.getLogger().warning("Error scanning chunk " + chunk.getX() + "," + chunk.getZ() +
                                " in world " + world.getName() + ": " + e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Error scanning dropped maces: " + e.getMessage());
        }

        return droppedMaceCount;
    }

    private void cleanupStaleMappings(Set<String> foundMaceIds, Set<UUID> validDrops) {
        try {
            // Remove mace IDs that were not found during scanning
            maceIdToHolder.entrySet().removeIf(entry -> !foundMaceIds.contains(entry.getKey()));

            // Clean up pending drops that are no longer relevant
            pendingDrops.entrySet().removeIf(entry -> !validDrops.contains(entry.getKey()));

            // Clean up stale pending drops (older than 30 minutes)
            long currentTime = System.currentTimeMillis();
            long maxAge = 30 * 60 * 1000; // 30 minutes
            pendingDrops.entrySet().removeIf(entry ->
                    currentTime - entry.getValue().dropTime > maxAge);

        } catch (Exception e) {
            plugin.getLogger().warning("Error during cleanup: " + e.getMessage());
        }
    }

    /**
     * Safely update a player's mace count
     */
    public void updatePlayerMaceCount(UUID playerUUID, int change) {
        if (playerUUID == null) return;

        lock.writeLock().lock();
        try {
            int currentCount = currentMaceHolders.getOrDefault(playerUUID, 0);
            int newCount = Math.max(0, currentCount + change);

            if (newCount <= 0) {
                currentMaceHolders.remove(playerUUID);
                plugin.getLogger().info("Removed player " +
                        plugin.getServer().getOfflinePlayer(playerUUID).getName() +
                        " from mace holders (count reached 0)");
            } else {
                currentMaceHolders.put(playerUUID, newCount);
                plugin.getLogger().info("Updated mace count for player " +
                        plugin.getServer().getOfflinePlayer(playerUUID).getName() +
                        " to " + newCount);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Safely update total mace count
     */
    public void updateMaceCount(int change) {
        int newCount = maceCount.addAndGet(change);
        if (newCount < 0) {
            maceCount.set(0);
            newCount = 0;
        }
        plugin.getLogger().info("Updated total mace count by " + change + ". New total: " + newCount);
    }

    /**
     * Register a mace drop
     */
    public void registerMaceDrop(UUID itemEntityId, UUID playerId, String maceId) {
        if (itemEntityId != null && playerId != null) {
            MaceDropInfo dropInfo = new MaceDropInfo(playerId, maceId);
            pendingDrops.put(itemEntityId, dropInfo);

            plugin.getLogger().info("Registered mace drop by " +
                    plugin.getServer().getOfflinePlayer(playerId).getName() +
                    " (Mace ID: " + maceId + ")");
        }
    }

    /**
     * Handle mace pickup
     */
    public void handleMacePickup(UUID itemEntityId, UUID newHolderId, String maceId) {
        if (newHolderId == null || maceId == null) return;

        lock.writeLock().lock();
        try {
            // Handle pending drop transfer
            MaceDropInfo dropInfo = pendingDrops.remove(itemEntityId);
            if (dropInfo != null && !dropInfo.originalHolder.equals(newHolderId)) {
                updatePlayerMaceCount(dropInfo.originalHolder, -1);
            }

            // Add to new holder
            updatePlayerMaceCount(newHolderId, 1);
            maceIdToHolder.put(maceId, newHolderId);

            plugin.getLogger().info("Mace pickup handled. New holder: " +
                    plugin.getServer().getOfflinePlayer(newHolderId).getName() +
                    " (Mace ID: " + maceId + ")");
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Handle mace destruction/removal
     */
    public void handleMaceDestruction(String maceId, UUID lastHolderId) {
        lock.writeLock().lock();
        try {
            if (maceId != null) {
                maceIdToHolder.remove(maceId);
            }

            if (lastHolderId != null) {
                updatePlayerMaceCount(lastHolderId, -1);
            }

            updateMaceCount(-1);

            plugin.getLogger().info("Mace destruction handled. Mace ID: " + maceId +
                    ", Last holder: " + (lastHolderId != null ?
                    plugin.getServer().getOfflinePlayer(lastHolderId).getName() : "Unknown"));
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Get thread-safe copy of current holders
     */
    public Map<UUID, Integer> getCurrentHoldersCopy() {
        return new HashMap<>(currentMaceHolders);
    }

    /**
     * Get thread-safe copy of mace ID mappings
     */
    public Map<String, UUID> getMaceIdHoldersCopy() {
        return new HashMap<>(maceIdToHolder);
    }
}