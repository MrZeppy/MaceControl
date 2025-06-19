package me.zeppy.maceControl;

import org.bukkit.*;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.event.player.PlayerDropItemEvent;

import java.io.File;
import java.io.IOException;
import java.util.*;

public final class MaceControl extends JavaPlugin implements Listener{
    private static MaceControl instance;
    private final Map<UUID, Long> maceCommandCooldowns = new HashMap<>();
    private long MACE_COMMAND_COOLDOWN;
    private final Map<UUID /* item-entity */, UUID /* original holder */> pendingDrops = new HashMap<>();

    private int maceCount = 0;
    private int MAX_MACE_COUNT;

    // Tracks each mace's unique ID to the UUID of the last known holder
    private final Map<String, UUID> maceIdToHolder = new HashMap<>();

    // Who currently holds a mace and how many they hold
    private final Map<UUID, Integer> currentMaceHolders = new HashMap<>();

    private UUID lastHolderUUID = null;

    public static NamespacedKey MACE_ID_KEY;

    private UUID lastDropperUUID = null;
    private String lastDropperName = null;

    @Override
    public void onEnable() {
        instance = this;
        MACE_ID_KEY = new NamespacedKey(this, "mace_id");
        saveDefaultConfig();
        MAX_MACE_COUNT = getConfig().getInt("max-mace-count", 5);
        MACE_COMMAND_COOLDOWN = getConfig().getLong("mace-command-cooldown", 5);

        getServer().getPluginManager().registerEvents(this, this);

        // Load and refresh as before
        loadMaceData();
        refreshMaceTracking();
        getLogger().info("MaceControl Enabled! Total maces: "
                + getMaceCount()
                + ", tracked holders: "
                + currentMaceHolders.size());

        // === AUTOSAVE TASK ===
        // Save every 5 minutes (5 * 60 * 20 ticks)
        long saveIntervalTicks = 5L * 60 * 20;
        getServer().getScheduler().runTaskTimer(this, () -> {
            saveMaceData();
            getLogger().info("Autosaved mace data.");
        }, saveIntervalTicks, saveIntervalTicks);
    }

    @Override
    public void onDisable() {
        saveMaceData();
        getLogger().info("MaceControl Disabled!");
    }

    public long getMaceCommandCooldownMillis() {
        return MACE_COMMAND_COOLDOWN * 1000;
    }

    private void refreshMaceTracking() {
        // Keep previous offline holders
        Map<UUID, Integer> previousHolders = new HashMap<>(currentMaceHolders);

        // Reset total count
        maceCount = 0;

        Map<UUID, Integer> newHolders = new HashMap<>();
        Set<String> foundMaceIds = new HashSet<>();

        // Scan online players (including off-hand)
        for (Player player : getServer().getOnlinePlayers()) {
            int playerCount = 0;
            for (ItemStack item : player.getInventory().getContents()) {
                if (isUniqueMace(item)) {
                    playerCount++;
                    String id = getMaceID(item);
                    if (id != null) {
                        foundMaceIds.add(id);
                        maceIdToHolder.put(id, player.getUniqueId());
                    }
                }
            }
            ItemStack off = player.getInventory().getItemInOffHand();
            if (isUniqueMace(off)) {
                playerCount++;
                String id = getMaceID(off);
                if (id != null) {
                    foundMaceIds.add(id);
                    maceIdToHolder.put(id, player.getUniqueId());
                }
            }
            if (playerCount > 0) newHolders.put(player.getUniqueId(), playerCount);
            maceCount += playerCount;
        }

        // Scan dropped items
        for (World world : getServer().getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                for (Entity e : chunk.getEntities()) {
                    if (e instanceof Item dropped && isUniqueMace(dropped.getItemStack())) {
                        maceCount++;
                        String id = getMaceID(dropped.getItemStack());
                        if (id != null) {
                            foundMaceIds.add(id);
                            // Get the original holder of this dropped mace
                            UUID originalHolder = maceIdToHolder.get(id);
                            if (originalHolder != null) {
                                // Add them to newHolders so they show in /mace
                                newHolders.merge(originalHolder, 1, Integer::sum);
                            }
                        }
                    }
                }
            }
        }


        // Add offline holders back
        for (Map.Entry<UUID, Integer> entry : previousHolders.entrySet()) {
            UUID uuid = entry.getKey();
            if (!getServer().getOfflinePlayer(uuid).isOnline()) {
                newHolders.put(uuid, entry.getValue());
                maceCount += entry.getValue();
            }
        }

        // Replace current holders
        currentMaceHolders.clear();
        currentMaceHolders.putAll(newHolders);

        // Remove stale maceId mappings for returned items
        maceIdToHolder.entrySet().removeIf(e -> {
            UUID holder = e.getValue();
            return getServer().getOfflinePlayer(holder).isOnline() && !foundMaceIds.contains(e.getKey());
        });

        getLogger().info("REFRESH → total maces = " + maceCount + ", holders tracked = " + currentMaceHolders.size());
    }

    @SuppressWarnings("unchecked")
    private void loadMaceData() {
        File dataFolder = getDataFolder();
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }

        File dataFile = new File(getDataFolder(), "maceData.yml");

        if (!dataFile.exists()) {
            getLogger().info("No maceData.yml found, starting fresh.");
            return;
        }

        YamlConfiguration config = YamlConfiguration.loadConfiguration(dataFile);

        // Load mace count
        this.maceCount = config.getInt("maceCount", 0);
        getLogger().info("Loaded maceCount: " + this.maceCount);

        // Load maceIdToHolder map
        Map<String, Object> idToHolderRaw = config.getConfigurationSection("maceIdToHolder") != null
                ? config.getConfigurationSection("maceIdToHolder").getValues(false)
                : new HashMap<>();

        // Clear existing data before loading
        this.maceIdToHolder.clear();

        for (Map.Entry<String, Object> entry : idToHolderRaw.entrySet()) {
            try {
                this.maceIdToHolder.put(entry.getKey(), UUID.fromString((String) entry.getValue()));
            } catch (IllegalArgumentException e) {
                getLogger().warning("Invalid UUID for maceId: " + entry.getKey());
            }
        }
        getLogger().info("Loaded " + this.maceIdToHolder.size() + " mace ID mappings");

        // Load currentMaceHolders map
        Map<String, Object> holderMapRaw = config.getConfigurationSection("currentMaceHolders") != null
                ? config.getConfigurationSection("currentMaceHolders").getValues(false)
                : new HashMap<>();

        // Clear existing data before loading
        this.currentMaceHolders.clear();

        for (Map.Entry<String, Object> entry : holderMapRaw.entrySet()) {
            try {
                UUID uuid = UUID.fromString(entry.getKey());
                int count = entry.getValue() instanceof Integer ? (int) entry.getValue() : 0;
                this.currentMaceHolders.put(uuid, count);
            } catch (Exception e) {
                getLogger().warning("Failed to parse mace holder entry: " + entry + ", Error: " + e.getMessage());
            }
        }
        getLogger().info("Loaded " + this.currentMaceHolders.size() + " mace holders");

        getLogger().info("Mace data loaded successfully.");
    }

    public int getMaxMaceCount() {
        return MAX_MACE_COUNT;
    }

    private void saveMaceData() {
        File dataFolder = getDataFolder();
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }

        File dataFile = new File(getDataFolder(), "maceData.yml");
        YamlConfiguration config = new YamlConfiguration();

        // Save mace count
        config.set("maceCount", this.maceCount);

        // Save maceIdToHolder
        Map<String, String> idToHolderStr = new HashMap<>();
        for (Map.Entry<String, UUID> entry : maceIdToHolder.entrySet()) {
            idToHolderStr.put(entry.getKey(), entry.getValue().toString());
        }
        config.createSection("maceIdToHolder", idToHolderStr);

        // Save currentMaceHolders
        Map<String, Integer> holderCountsStr = new HashMap<>();
        for (Map.Entry<UUID, Integer> entry : currentMaceHolders.entrySet()) {
            holderCountsStr.put(entry.getKey().toString(), entry.getValue());
        }
        config.createSection("currentMaceHolders", holderCountsStr);

        try {
            config.save(dataFile);
            getLogger().info("Mace data saved. Current holders: " + currentMaceHolders.size());
        } catch (IOException e) {
            getLogger().severe("Failed to save mace data: " + e.getMessage());
        }
    }


    public static MaceControl getInstance() {
        return instance;
    }

    // Returns the map of holders with their mace counts
    public Map<UUID, Integer> getCurrentMaceHolderMap() {
        return currentMaceHolders;
    }

    // Returns the set of UUIDs currently holding maces
    public Set<UUID> getCurrentMaceHolders() {
        return currentMaceHolders.keySet();
    }

    public int getMaceCount() {
        return maceCount;
    }

    public void incrementMaceCount() {
        maceCount++;
    }

    public void decrementMaceCount() {
        maceCount = Math.max(0, maceCount - 1);
    }

    public Map<String, UUID> getMaceIdToHolder() {
        return maceIdToHolder;
    }

    public void removeMaceHolder(UUID playerUUID) {
        currentMaceHolders.remove(playerUUID);
    }

    public ItemStack tagMace(ItemStack mace, UUID creatorUUID) {
        if (mace == null || mace.getType() == Material.AIR) return mace;

        ItemMeta meta = mace.getItemMeta();
        if (meta == null) return mace;

        PersistentDataContainer container = meta.getPersistentDataContainer();

        if (!container.has(MACE_ID_KEY, PersistentDataType.STRING)) {
            String uniqueId = UUID.randomUUID().toString();
            container.set(MACE_ID_KEY, PersistentDataType.STRING, uniqueId);
            mace.setItemMeta(meta);

            maceIdToHolder.put(uniqueId, creatorUUID);
        }

        return mace;
    }

    public static String getMaceID(ItemStack item) {
        if (item == null || item.getType() != Material.MACE) return null;

        ItemMeta meta = item.getItemMeta();
        if (meta == null) return null;

        PersistentDataContainer container = meta.getPersistentDataContainer();
        if (!container.has(MACE_ID_KEY, PersistentDataType.STRING)) return null;

        return container.get(MACE_ID_KEY, PersistentDataType.STRING);
    }

    // Replace this with your actual check for mace uniqueness/tag
    private boolean isUniqueMace(ItemStack item) {
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
        boolean hasKey = container.has(MACE_ID_KEY, PersistentDataType.STRING);
        return hasKey;
    }


    @EventHandler
    public void onCraft(CraftItemEvent event) {
        ItemStack result = event.getRecipe().getResult();
        if (result == null || result.getType() != Material.MACE) return;
        if (isAutoCrafter(event.getInventory())) { event.setCancelled(true); return; }
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.isShiftClick()) {
            event.setCancelled(true);
            player.sendMessage("§cYou cannot shift-click craft the Mace!");
            return;
        }

        refreshMaceTracking();
        if (maceCount >= MAX_MACE_COUNT) {
            event.setCancelled(true);
            player.sendMessage("§cThere are already " + MAX_MACE_COUNT + " Maces in the world!");
            return;
        }

        ItemStack mace = tagMace(result.clone(), player.getUniqueId());
        event.getInventory().setResult(mace);
        currentMaceHolders.merge(player.getUniqueId(), 1, Integer::sum);
        maceCount++;

        player.sendMessage("§6You have crafted a Mace! (" + maceCount + "/" + MAX_MACE_COUNT + ")");
        getLogger().info("Mace crafted by: " + player.getName());
    }




    @EventHandler
    public void onInteractWithItemFrame(PlayerInteractEntityEvent event) {
        if (event.getRightClicked() instanceof ItemFrame) {
            ItemStack item = event.getPlayer().getInventory().getItemInMainHand();
            if (isUniqueMace(item)) {
                event.setCancelled(true);
                event.getPlayer().sendMessage("§cYou cannot place the Mace in an item frame!");
            }
        }
    }

    @EventHandler
    public void onInteractWithArmorStand(PlayerInteractAtEntityEvent event) {
        if (event.getRightClicked() instanceof ArmorStand) {
            ItemStack item = event.getPlayer().getInventory().getItemInMainHand();
            if (isUniqueMace(item)) {
                event.setCancelled(true);
                event.getPlayer().sendMessage("§cYou cannot place the Mace on an armor stand!");
            }
        }
    }

    // Prevent hopper from sucking item in
    @EventHandler
    public void onInventoryPickupItem(InventoryPickupItemEvent event) {
        ItemStack item = event.getItem().getItemStack();
        if (isUniqueMace(item) || isHeavyCore(item)) {
            event.setCancelled(true);
        }
    }

    // Prevent hopper and other automatic inventory moves
    @EventHandler
    public void onInventoryMoveItem(InventoryMoveItemEvent event) {
        ItemStack item = event.getItem();
        if (isUniqueMace(item) || isHeavyCore(item)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        Inventory inventory = event.getInventory();
        if (inventory == null) return;

        if (isAutoCrafter(inventory)) {
            Map<Integer, ItemStack> newItems = event.getNewItems();
            for (ItemStack item : newItems.values()) {
                if (item != null && isHeavyCore(item)) {
                    event.setCancelled(true);
                    if (event.getWhoClicked() instanceof Player player) {
                        player.sendMessage("§cYou cannot drag the Heavy Core into the Auto Crafter!");
                    }
                    return;
                }
            }
        }
    }

    // Prevent player manually putting mace into containers (chests, barrels, shulker boxes, etc)
    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getClickedInventory() == null) return;

        Inventory clickedInventory = event.getClickedInventory();
        InventoryType clickedInvType = clickedInventory.getType();
        Inventory topInventory = event.getView().getTopInventory();
        InventoryType topType = topInventory.getType();
        Player player = (Player) event.getWhoClicked();

        // Prevent shift-clicking the crafting output slot to duplicate the Mace
        ItemStack current = event.getCurrentItem();
        if ((event.getClick() == ClickType.SHIFT_LEFT || event.getClick() == ClickType.SHIFT_RIGHT)
                && current != null
                && isUniqueMace(current)
                && (topType == InventoryType.CRAFTING || topType == InventoryType.WORKBENCH)
                && event.getSlot() == 0) { // crafting output slot is slot 0
            event.setCancelled(true);
            player.sendMessage("§cYou cannot shift-click the Mace from the crafting output!");
            return;
        }

        // --- Your existing code here ---

        // Normal placing with cursor
        ItemStack cursor = event.getCursor();
        if (cursor != null) {
            if (isUniqueMace(cursor) && isBlockedContainer(clickedInvType)) {
                event.setCancelled(true);
                player.sendMessage("§cYou cannot put the Mace in a container");
                return;
            }
            if (isHeavyCore(cursor) && isAutoCrafter(clickedInventory)) {
                event.setCancelled(true);
                player.sendMessage("§cYou cannot put the Heavy Core in the Auto Crafter");
                return;
            }
        }

        // Shift-click move
        if ((event.getClick() == ClickType.SHIFT_LEFT || event.getClick() == ClickType.SHIFT_RIGHT) && current != null) {
            if (isUniqueMace(current) && isBlockedContainer(topType)) {
                event.setCancelled(true);
                player.sendMessage("§cYou cannot put the Mace in a container!");
                return;
            }

            Inventory destinationInventory;
            if (clickedInventory.getType() == InventoryType.PLAYER) {
                destinationInventory = topInventory;
            } else {
                destinationInventory = event.getView().getBottomInventory();
            }

            if (isHeavyCore(current) && isAutoCrafter(destinationInventory)) {
                event.setCancelled(true);
                player.sendMessage("§cYou cannot put the Heavy Core in the Auto Crafter!");
                return;
            }
        }

        // Hotkey (number key) swap
        if (event.getClick() == ClickType.NUMBER_KEY) {
            int hotbarButton = event.getHotbarButton();
            if (hotbarButton < 0 || hotbarButton > 8) return;

            int clickedSlot = event.getSlot();
            ItemStack hotbarItem = player.getInventory().getItem(hotbarButton);
            InventoryView view = event.getView();

            Inventory clickedSlotInventory = null;
            if (clickedSlot >= 0) {
                if (clickedSlot < view.getTopInventory().getSize()) {
                    clickedSlotInventory = view.getTopInventory();
                } else {
                    clickedSlotInventory = view.getBottomInventory();
                }
            }

            if (clickedSlotInventory != null && hotbarItem != null) {
                if (isUniqueMace(hotbarItem) && isBlockedContainer(clickedSlotInventory.getType())) {
                    event.setCancelled(true);
                    player.sendMessage("§cYou cannot put the Mace in a container!");
                } else if (isHeavyCore(hotbarItem) && isAutoCrafter(clickedInventory)) {
                    event.setCancelled(true);
                    player.sendMessage("§cYou cannot put the Heavy Core in the Auto Crafter!");
                }
            }

            // Prevent shift-click moving Mace into inventory containing bundles
            if ((event.getClick() == ClickType.SHIFT_LEFT || event.getClick() == ClickType.SHIFT_RIGHT)
                    && current != null && isUniqueMace(current)
                    && clickedInventory.getType() == InventoryType.PLAYER) {

                event.setCancelled(true);
                player.sendMessage("§cYou cannot put the Mace into a bundle!");
                return;
            }

            // Prevent hotkey swapping Mace with bundle item
            if (event.getClick() == ClickType.NUMBER_KEY) {
                if ((hotbarItem != null && isUniqueMace(hotbarItem) && isBundle(current))
                        || (cursor != null && isUniqueMace(cursor) && isBundle(current))) {
                    event.setCancelled(true);
                    player.sendMessage("§cYou cannot put the Mace into a bundle!");
                    return;
                }
            }
        }
    }

    @EventHandler
    public void onBundleInsert(InventoryClickEvent event) {
        ItemStack clicked = event.getCurrentItem();
        ItemStack cursor = event.getCursor();

        if (clicked == null || cursor == null) return;

        // Player trying to insert the mace into a bundle
        if (clicked.getType() == Material.BUNDLE && isUniqueMace(cursor)) {
            event.setCancelled(true);
            event.getWhoClicked().sendMessage(ChatColor.RED + "You cannot put the Mace into a bundle!");
        }

        // Player trying to insert an item (possibly the mace) into a bundle they're holding
        if (cursor.getType() == Material.BUNDLE && isUniqueMace(clicked)) {
            event.setCancelled(true);
            event.getWhoClicked().sendMessage(ChatColor.RED + "You cannot put the Mace into a bundle!");
        }
    }


    // Define allowed vs blocked containers
    private boolean isBlockedContainer(InventoryType type) {
        return type != InventoryType.PLAYER
                && type != InventoryType.ANVIL
                && type != InventoryType.GRINDSTONE
                && type != InventoryType.ENCHANTING;
    }

    @EventHandler
    public void onEntityPickupItem(EntityPickupItemEvent event) {
        // Prevent non-players from picking up maces
        if (!(event.getEntity() instanceof Player)) {
            Item entityItem = event.getItem();
            ItemStack stack = entityItem.getItemStack();
            if (isUniqueMace(stack)) {
                event.setCancelled(true);
            }
        }
    }

    private boolean isHeavyCore(ItemStack item) {
        if (item == null) return false;
        return item.getType() == Material.HEAVY_CORE;
    }

    private boolean isAutoCrafter(Inventory inventory) {
        if (inventory == null) return false;
        InventoryHolder holder = inventory.getHolder();
        if (holder instanceof BlockInventoryHolder) {
            BlockInventoryHolder blockHolder = (BlockInventoryHolder) holder;
            Material blockType = blockHolder.getBlock().getType();
            return blockType == Material.CRAFTER; // Use the correct enum for the auto crafter block
        }
        return false;
    }

    public boolean isBundle(ItemStack item) {
        if (item == null) return false;
        if (item.getType() != Material.BUNDLE) return false;
        if (!(item.getItemMeta() instanceof BundleMeta)) return false;
        return true;
    }

    // Called when the mace breaks from durability loss in a player's hand
    @EventHandler
    public void onItemBreak(PlayerItemDamageEvent event) {
        ItemStack item = event.getItem();

        if (!isUniqueMace(item)) return;

        short currentDamage = item.getDurability();
        int incomingDamage = event.getDamage();
        int maxDurability = item.getType().getMaxDurability();

        if (currentDamage + incomingDamage >= maxDurability) {
            Player player = event.getPlayer();
            UUID playerUUID = player.getUniqueId();

            String maceId = getMaceID(item);
            if (maceId != null) {
                maceIdToHolder.remove(maceId);
            }

            // Decrement mace count safely (no negatives)
            decrementMaceCount();

            Integer count = currentMaceHolders.get(playerUUID);
            if (count != null) {
                if (count <= 1) {
                    currentMaceHolders.remove(playerUUID);
                    getLogger().info("Removed player " + player.getName() + " from mace holders due to mace break.");
                } else {
                    currentMaceHolders.put(playerUUID, count - 1);
                    getLogger().info("Decremented mace count for player " + player.getName() + ". New count: " + (count - 1));
                }
            }

            lastHolderUUID = playerUUID;
            getLogger().info("The Mace broke from durability loss by player " + player.getName() + ".");
            Bukkit.broadcastMessage("§cA Mace has broke from durability loss and can now be crafted again!\n" +
                    "Total remaining maces: §e" + maceCount + "§7/§e" + MAX_MACE_COUNT);;
        }
    }

    // Called when player dies holding the mace and falls in void
    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        UUID playerUUID = player.getUniqueId();

        if (player.getLastDamageCause() != null &&
                player.getLastDamageCause().getCause() == EntityDamageEvent.DamageCause.VOID) {

            for (ItemStack drop : new ArrayList<>(event.getDrops())) {
                if (isUniqueMace(drop)) {
                    String maceId = getMaceID(drop);
                    if (maceId != null) {
                        maceIdToHolder.remove(maceId);
                    }

                    decrementMaceCount();

                    int count = currentMaceHolders.getOrDefault(playerUUID, 0);
                    if (count <= 1) {
                        currentMaceHolders.remove(playerUUID);
                    } else {
                        currentMaceHolders.put(playerUUID, count - 1);
                    }

                    // Remove the mace from drops
                    event.getDrops().remove(drop);

                    getLogger().info("Player died in void with mace. Updated mace holders and count.");
                    Bukkit.broadcastMessage("§cA Mace has fallen into the void and can now be crafted again!\n"  +
                            "Total remaining maces: §e" + maceCount + "§7/§e" + MAX_MACE_COUNT);
                }
            }
        } else {
            // Handle all other deaths - DON'T remove from holder tracking yet
            // The mace will drop and should remain tracked until it despawns
            for (ItemStack drop : event.getDrops()) {
                if (isUniqueMace(drop)) {
                    // Set last dropper info for /mace command
                    lastDropperUUID = playerUUID;
                    lastDropperName = player.getName();

                    getLogger().info("Mace dropped by " + player.getName() + " on death - will remain tracked until despawn");
                }
            }
        }
    }
    @EventHandler
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        Item dropped = event.getItemDrop();
        if (!isUniqueMace(dropped.getItemStack())) return;

        UUID playerId = event.getPlayer().getUniqueId();
        pendingDrops.put(dropped.getUniqueId(), playerId);

        // Set last dropper info for /mace command
        lastDropperUUID = playerId;
        lastDropperName = event.getPlayer().getName();

        // DON'T remove from holder tracking when dropped manually
        // The mace still exists and should count toward the limit

        getLogger().info("Registered dropped mace by "
                + event.getPlayer().getName()
                + " (will remain tracked until destroyed/picked up).");
    }


    @EventHandler
    public void onItemRemove(EntityRemoveEvent event) {
        if (!(event.getEntity() instanceof Item itemEnt)) return;
        ItemStack stack = itemEnt.getItemStack();
        if (!isUniqueMace(stack)) return;

        // Check if this was a pending drop
        UUID originalHolder = pendingDrops.remove(itemEnt.getUniqueId());

        String maceId = getMaceID(stack);
        UUID holderUUID = null;

        if (maceId != null) {
            holderUUID = maceIdToHolder.remove(maceId);
        }

        // Use original holder if available, otherwise use maceId holder
        UUID playerToRemove = originalHolder != null ? originalHolder : holderUUID;

        if (playerToRemove != null) {
            int count = currentMaceHolders.getOrDefault(playerToRemove, 0);
            if (count <= 1) {
                currentMaceHolders.remove(playerToRemove);
            } else {
                currentMaceHolders.put(playerToRemove, count - 1);
            }
        }

        decrementMaceCount();
        Bukkit.broadcastMessage("§cA Mace was destroyed and can now be crafted again!\n" +
                "Total remaining maces: §e" + maceCount + "§7/§e" + MAX_MACE_COUNT);

        getLogger().info("Mace removed/despawned - now craftable again");
    }
    // Called when player picks up the mace item
    @EventHandler
    public void onPlayerPickupItem(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;

        Item entityItem = event.getItem();
        ItemStack stack = entityItem.getItemStack();
        if (!isUniqueMace(stack)) return;

        String maceID = getMaceID(stack);
        if (maceID == null) return;

        // Get the original holder of this mace
        UUID originalHolder = pendingDrops.get(entityItem.getUniqueId());

        // Remove from pending drops
        pendingDrops.remove(entityItem.getUniqueId());

        // If this was a pending drop, we need to transfer the holder tracking
        if (originalHolder != null) {
            // Remove from original holder
            int originalCount = currentMaceHolders.getOrDefault(originalHolder, 0);
            if (originalCount <= 1) {
                currentMaceHolders.remove(originalHolder);
            } else {
                currentMaceHolders.put(originalHolder, originalCount - 1);
            }

            // Add to new holder
            currentMaceHolders.merge(player.getUniqueId(), 1, Integer::sum);
        } else {
            // This is a death drop or old drop - just add to new holder
            // Don't increment mace count - it's already counted
            currentMaceHolders.merge(player.getUniqueId(), 1, Integer::sum);
        }

        // Update the mace ownership
        maceIdToHolder.put(maceID, player.getUniqueId());
        lastHolderUUID = player.getUniqueId();

        player.sendMessage(ChatColor.GOLD + "You have picked up the Mace!");
        getLogger().info("Mace picked up by: " + player.getName() + " (ID: " + maceID + ")");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // Admin-only command: /droppedmace
        if (command.getName().equalsIgnoreCase("droppedmace")) {
            if (!sender.isOp()) {
                sender.sendMessage(ChatColor.RED + "You must be OP to use this command.");
                return true;
            }

            sender.sendMessage(ChatColor.AQUA + "[MaceAdmin] Currently dropped (temp) maces:");

            if (pendingDrops.isEmpty()) {
                sender.sendMessage(ChatColor.GRAY + "- None");
            } else {
                for (Map.Entry<UUID, UUID> entry : pendingDrops.entrySet()) {
                    UUID itemUUID = entry.getKey();
                    UUID playerUUID = entry.getValue();
                    String playerName = Bukkit.getOfflinePlayer(playerUUID).getName();
                    sender.sendMessage(ChatColor.GRAY + "- Item: " + itemUUID + " | Dropped by: " + playerName);
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

            refreshMaceTracking();

            if (currentMaceHolders.isEmpty()) {
                sender.sendMessage(ChatColor.RED + "The mace is not currently held by anyone.");
            } else {
                StringBuilder holders = new StringBuilder();
                for (Map.Entry<UUID, Integer> entry : currentMaceHolders.entrySet()) {
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
}
