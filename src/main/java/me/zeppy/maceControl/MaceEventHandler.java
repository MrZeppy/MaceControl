package me.zeppy.maceControl;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.BlockInventoryHolder;
import org.bukkit.inventory.meta.BundleMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class MaceEventHandler implements Listener {
    private final MaceControl plugin;
    private final MaceManager manager;
    private final MaceTracker tracker;

    public MaceEventHandler(MaceControl plugin) {
        this.plugin = plugin;
        this.manager = plugin.getManager();
        this.tracker = plugin.getTracker();
    }

    // Helper methods
    private boolean isUniqueMace(ItemStack item) {
        return manager.isUniqueMace(item);
    }

    private String getMaceID(ItemStack item) {
        return MaceManager.getMaceID(item);
    }

    private boolean hasPermission(Player player, String permission) {
        return player.hasPermission(permission);
    }

    private boolean isWorldAllowed(String worldName) {
        List<String> allowedWorlds = plugin.getConfig().getStringList("worlds.allowed-craft-worlds");
        List<String> restrictedWorlds = plugin.getConfig().getStringList("worlds.restricted-worlds");

        if (!allowedWorlds.isEmpty() && !allowedWorlds.contains(worldName)) {
            return false;
        }

        if (!restrictedWorlds.isEmpty() && restrictedWorlds.contains(worldName)) {
            return false;
        }

        return true;
    }

    private boolean isRestrictionEnabled(String restriction) {
        return plugin.getConfig().getBoolean("restrictions." + restriction, true);
    }

    private boolean isBlockedContainer(InventoryType type) {
        if (!isRestrictionEnabled("block-containers")) return false;

        boolean allowEnderChest = plugin.getConfig().getBoolean("restrictions.allow-ender-chest", false);
        boolean allowShulkerBoxes = plugin.getConfig().getBoolean("restrictions.allow-shulker-boxes", false);

        if (type == InventoryType.ENDER_CHEST && allowEnderChest) return false;
        if (type == InventoryType.SHULKER_BOX && allowShulkerBoxes) return false;

        return type != InventoryType.PLAYER
                && type != InventoryType.ANVIL
                && type != InventoryType.GRINDSTONE
                && type != InventoryType.ENCHANTING;
    }

    private boolean isHeavyCore(ItemStack item) {
        return item != null && item.getType() == Material.HEAVY_CORE;
    }

    private boolean isAutoCrafter(Inventory inventory) {
        if (inventory == null) return false;
        InventoryHolder holder = inventory.getHolder();
        if (holder instanceof BlockInventoryHolder) {
            BlockInventoryHolder blockHolder = (BlockInventoryHolder) holder;
            Material blockType = blockHolder.getBlock().getType();
            return blockType == Material.CRAFTER;
        }
        return false;
    }

    private boolean isBundle(ItemStack item) {
        return item != null &&
                item.getType() == Material.BUNDLE &&
                item.getItemMeta() instanceof BundleMeta;
    }

    private void broadcastMaceDestroyed() {
        if (plugin.getConfig().getBoolean("messages.broadcast-destruction", true)) {
            Bukkit.broadcastMessage("§cA Mace was destroyed and can now be crafted again!\n" +
                    "Total remaining maces: §e" + tracker.getMaceCount() + "§7/§e" + manager.getMaxMaceCount());
        }
    }

    @EventHandler
    public void onCraft(CraftItemEvent event) {
        ItemStack result = event.getRecipe().getResult();
        if (result == null || result.getType() != Material.MACE) return;

        // Prevent auto crafter usage
        if (isAutoCrafter(event.getInventory())) {
            event.setCancelled(true);
            return;
        }

        if (!(event.getWhoClicked() instanceof Player player)) return;

        // Check world restrictions (only if player doesn't have bypass permission)
        if (!hasPermission(player, plugin.getConfig().getString("bypass-permission", "macecontrol.bypass"))) {
            if (!isWorldAllowed(player.getWorld().getName())) {
                event.setCancelled(true);
                player.sendMessage("§cMace crafting is not allowed in this world!");
                return;
            }
        }

        // Prevent shift-click crafting
        if (event.isShiftClick()) {
            event.setCancelled(true);
            player.sendMessage("§cYou cannot shift-click craft the Mace!");
            return;
        }

        // Check mace count limit with synchronization to prevent race conditions
        synchronized (tracker) {
            tracker.refreshMaceTracking();
            if (!hasPermission(player, plugin.getConfig().getString("bypass-permission", "macecontrol.bypass"))) {
                if (tracker.getMaceCount() >= manager.getMaxMaceCount()) {
                    event.setCancelled(true);
                    player.sendMessage("§cThere are already " + manager.getMaxMaceCount() + " Maces in the world!");
                    return;
                }
            }

            // Pre-increment the count to reserve this slot while the mace is being created
            tracker.updateMaceCount(1);
        }

        // Create and tag the mace
        ItemStack mace = manager.tagMace(result.clone(), player.getUniqueId());
        event.getInventory().setResult(mace);

        // NOW update player tracking and mace ID mapping
        tracker.updatePlayerMaceCount(player.getUniqueId(), 1);
        // Note: Total count already updated above in synchronized block

        // Update mace ID mapping
        String maceId = getMaceID(mace);
        if (maceId != null) {
            tracker.getMaceIdToHolder().put(maceId, player.getUniqueId());
        }

        // Send success message
        player.sendMessage("§6You have crafted a Mace! (" + tracker.getMaceCount() + "/" + manager.getMaxMaceCount() + ")");

        // Broadcast craft notification if enabled
        if (plugin.getConfig().getBoolean("messages.broadcast-craft", true)) {
            String crafterName = player.getName();
            Bukkit.broadcastMessage("§6" + crafterName + " has crafted a Mace! (" + tracker.getMaceCount() + "/" + manager.getMaxMaceCount() + ")");
        }

        plugin.getLogger().info("Mace crafted by: " + player.getName() + " (Total: " + tracker.getMaceCount() + "/" + manager.getMaxMaceCount() + ")");
    }

    @EventHandler
    public void onInteractWithItemFrame(PlayerInteractEntityEvent event) {
        if (event.getRightClicked() instanceof ItemFrame) {
            ItemStack item = event.getPlayer().getInventory().getItemInMainHand();
            if (isUniqueMace(item)) {
                Player player = event.getPlayer();
                if (!hasPermission(player, plugin.getConfig().getString("bypass-permission", "macecontrol.bypass"))
                        && isRestrictionEnabled("block-item-frames")) {
                    event.setCancelled(true);
                    player.sendMessage("§cYou cannot place the Mace in an item frame!");
                }
            }
        }
    }

    @EventHandler
    public void onInteractWithArmorStand(PlayerInteractAtEntityEvent event) {
        if (event.getRightClicked() instanceof ArmorStand) {
            ItemStack item = event.getPlayer().getInventory().getItemInMainHand();
            if (isUniqueMace(item)) {
                Player player = event.getPlayer();
                if (!hasPermission(player, plugin.getConfig().getString("bypass-permission", "macecontrol.bypass"))
                        && isRestrictionEnabled("block-armor-stands")) {
                    event.setCancelled(true);
                    player.sendMessage("§cYou cannot place the Mace on an armor stand!");
                }
            }
        }
    }

    @EventHandler
    public void onInventoryPickupItem(InventoryPickupItemEvent event) {
        ItemStack item = event.getItem().getItemStack();
        if (isUniqueMace(item) || isHeavyCore(item)) {
            event.setCancelled(true);
        }
    }

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

        Player player = (Player) event.getWhoClicked();

        // Check if player has bypass permission
        boolean hasBypass = hasPermission(player, plugin.getConfig().getString("bypass-permission", "macecontrol.bypass"));

        // Handle auto crafter restrictions for heavy cores
        if (isAutoCrafter(inventory)) {
            Map<Integer, ItemStack> newItems = event.getNewItems();
            for (ItemStack item : newItems.values()) {
                if (item != null && isHeavyCore(item)) {
                    if (!hasBypass && isRestrictionEnabled("block-heavy-core-autocrafter")) {
                        event.setCancelled(true);
                        player.sendMessage("§cYou cannot drag the Heavy Core into the Auto Crafter!");
                        return;
                    }
                }
            }
        }

        // Handle mace drag restrictions
        Map<Integer, ItemStack> newItems = event.getNewItems();
        for (ItemStack item : newItems.values()) {
            if (item != null && isUniqueMace(item)) {
                if (!hasBypass && isBlockedContainer(inventory.getType())) {
                    event.setCancelled(true);
                    player.sendMessage("§cYou cannot drag the Mace into a container!");
                    return;
                }
            }
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getClickedInventory() == null) return;

        Inventory clickedInventory = event.getClickedInventory();
        InventoryType clickedInvType = clickedInventory.getType();
        Inventory topInventory = event.getView().getTopInventory();
        InventoryType topType = topInventory.getType();
        Player player = (Player) event.getWhoClicked();

        // Check if player has bypass permission
        boolean hasBypass = hasPermission(player, plugin.getConfig().getString("bypass-permission", "macecontrol.bypass"));

        // Prevent shift-clicking the crafting output slot to duplicate the Mace
        ItemStack current = event.getCurrentItem();
        if ((event.getClick() == ClickType.SHIFT_LEFT || event.getClick() == ClickType.SHIFT_RIGHT)
                && current != null
                && isUniqueMace(current)
                && (topType == InventoryType.CRAFTING || topType == InventoryType.WORKBENCH)
                && event.getSlot() == 0) {
            event.setCancelled(true);
            player.sendMessage("§cYou cannot shift-click the Mace from the crafting output!");
            return;
        }

        // Handle cursor item placement
        ItemStack cursor = event.getCursor();
        if (cursor != null) {
            if (isUniqueMace(cursor) && !hasBypass && isBlockedContainer(clickedInvType)) {
                event.setCancelled(true);
                player.sendMessage("§cYou cannot put the Mace in a container");
                return;
            }
            if (isHeavyCore(cursor) && !hasBypass && isRestrictionEnabled("block-heavy-core-autocrafter") && isAutoCrafter(clickedInventory)) {
                event.setCancelled(true);
                player.sendMessage("§cYou cannot put the Heavy Core in the Auto Crafter");
                return;
            }
        }

        // Handle shift-click moves
        if ((event.getClick() == ClickType.SHIFT_LEFT || event.getClick() == ClickType.SHIFT_RIGHT) && current != null) {
            if (isUniqueMace(current) && !hasBypass && isBlockedContainer(topType)) {
                event.setCancelled(true);
                player.sendMessage("§cYou cannot put the Mace in a container!");
                return;
            }

            Inventory destinationInventory = clickedInventory.getType() == InventoryType.PLAYER
                    ? topInventory
                    : event.getView().getBottomInventory();

            if (isHeavyCore(current) && !hasBypass && isRestrictionEnabled("block-heavy-core-autocrafter") && isAutoCrafter(destinationInventory)) {
                event.setCancelled(true);
                player.sendMessage("§cYou cannot put the Heavy Core in the Auto Crafter!");
                return;
            }

            // Prevent shift-click moving Mace into bundle
            if (isUniqueMace(current) && !hasBypass && isRestrictionEnabled("block-bundles") && clickedInventory.getType() == InventoryType.PLAYER) {
                // Check if destination has bundles
                for (ItemStack item : destinationInventory.getContents()) {
                    if (isBundle(item)) {
                        event.setCancelled(true);
                        player.sendMessage("§cYou cannot put the Mace into a bundle!");
                        return;
                    }
                }
            }
        }

        // Handle hotkey (number key) swaps
        if (event.getClick() == ClickType.NUMBER_KEY) {
            int hotbarButton = event.getHotbarButton();
            if (hotbarButton < 0 || hotbarButton > 8) return;

            ItemStack hotbarItem = player.getInventory().getItem(hotbarButton);
            InventoryView view = event.getView();
            int clickedSlot = event.getSlot();

            Inventory clickedSlotInventory = null;
            if (clickedSlot >= 0) {
                clickedSlotInventory = clickedSlot < view.getTopInventory().getSize()
                        ? view.getTopInventory()
                        : view.getBottomInventory();
            }

            if (clickedSlotInventory != null && hotbarItem != null) {
                if (isUniqueMace(hotbarItem) && !hasBypass && isBlockedContainer(clickedSlotInventory.getType())) {
                    event.setCancelled(true);
                    player.sendMessage("§cYou cannot put the Mace in a container!");
                    return;
                }
                if (isHeavyCore(hotbarItem) && !hasBypass && isRestrictionEnabled("block-heavy-core-autocrafter") && isAutoCrafter(clickedInventory)) {
                    event.setCancelled(true);
                    player.sendMessage("§cYou cannot put the Heavy Core in the Auto Crafter!");
                    return;
                }
            }

            // Prevent hotkey swapping Mace with bundle item
            if (!hasBypass && isRestrictionEnabled("block-bundles")) {
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

        Player player = (Player) event.getWhoClicked();
        boolean hasBypass = hasPermission(player, plugin.getConfig().getString("bypass-permission", "macecontrol.bypass"));

        // Player trying to insert mace into bundle or vice versa
        if (!hasBypass && isRestrictionEnabled("block-bundles")) {
            if ((clicked.getType() == Material.BUNDLE && isUniqueMace(cursor))
                    || (cursor.getType() == Material.BUNDLE && isUniqueMace(clicked))) {
                event.setCancelled(true);
                event.getWhoClicked().sendMessage(ChatColor.RED + "You cannot put the Mace into a bundle!");
            }
        }
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

            // Handle mace destruction using tracker
            tracker.handleMaceDestruction(maceId, playerUUID);
            broadcastMaceDestroyed();

            plugin.getLogger().info("The Mace broke from durability loss by player " + player.getName() + ".");
        }
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        UUID playerUUID = player.getUniqueId();

        // Handle void deaths - mace is completely lost
        if (player.getLastDamageCause() != null &&
                player.getLastDamageCause().getCause() == EntityDamageEvent.DamageCause.VOID) {

            for (ItemStack drop : new ArrayList<>(event.getDrops())) {
                if (isUniqueMace(drop)) {
                    String maceId = getMaceID(drop);
                    tracker.handleMaceDestruction(maceId, playerUUID);
                    event.getDrops().remove(drop);

                    plugin.getLogger().info("Player died in void with mace. Updated mace holders and count.");
                    if (plugin.getConfig().getBoolean("messages.broadcast-destruction", true)) {
                        Bukkit.broadcastMessage("§cA Mace has fallen into the void and can now be crafted again!\n" +
                                "Total remaining maces: §e" + tracker.getMaceCount() + "§7/§e" + manager.getMaxMaceCount());
                    }
                }
            }
        } else {
            // Handle normal deaths - mace drops and remains tracked
            for (ItemStack drop : event.getDrops()) {
                if (isUniqueMace(drop)) {
                    plugin.getLogger().info("Mace dropped by " + player.getName() + " on death - will remain tracked until despawn");
                }
            }
        }
    }

    @EventHandler
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        Item dropped = event.getItemDrop();
        if (!isUniqueMace(dropped.getItemStack())) return;

        UUID playerId = event.getPlayer().getUniqueId();
        String maceId = getMaceID(dropped.getItemStack());

        // Use new tracker method
        tracker.registerMaceDrop(dropped.getUniqueId(), playerId, maceId);

        plugin.getLogger().info("Registered dropped mace by " + event.getPlayer().getName() +
                " (will remain tracked until destroyed/picked up).");
    }

    @EventHandler
    public void onItemRemove(EntityRemoveEvent event) {
        if (!(event.getEntity() instanceof Item itemEnt)) return;
        ItemStack stack = itemEnt.getItemStack();
        if (!isUniqueMace(stack)) return;

        String maceId = getMaceID(stack);
        UUID originalHolder = null;

        // Get original holder from pending drops (using new method)
        Map<UUID, MaceTracker.MaceDropInfo> pendingDropsNew = tracker.getPendingDropsNew();
        MaceTracker.MaceDropInfo dropInfo = pendingDropsNew.remove(itemEnt.getUniqueId());
        if (dropInfo != null) {
            originalHolder = dropInfo.originalHolder;
        }

        // Handle destruction using tracker
        tracker.handleMaceDestruction(maceId, originalHolder);
        broadcastMaceDestroyed();
        plugin.getLogger().info("Mace removed/despawned - now craftable again");
    }

    @EventHandler
    public void onPlayerPickupItem(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;

        Item entityItem = event.getItem();
        ItemStack stack = entityItem.getItemStack();
        if (!isUniqueMace(stack)) return;

        String maceID = getMaceID(stack);
        if (maceID == null) return;

        // Handle pickup using tracker
        tracker.handleMacePickup(entityItem.getUniqueId(), player.getUniqueId(), maceID);

        player.sendMessage(ChatColor.GOLD + "You have picked up the Mace!");
        plugin.getLogger().info("Mace picked up by: " + player.getName() + " (ID: " + maceID + ")");
    }
}