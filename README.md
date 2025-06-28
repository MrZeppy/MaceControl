# MaceControl – Limit Maces & Prevent Storage | Minecraft Plugin (1.21+)

MaceControl is a lightweight Minecraft plugin that limits the number of maces allowed on your server and prevents players from storing maces in containers like chests, bundles, and item frames. It also prevents mobs from picking up maces and tracks all maces across online/offline players and dropped items.

## Features

- Mace Limits: Configurable mace count limits (default: 5 total server-wide)
- Storage Prevention: Prevents placing maces into chests, bundles, and item frames
- Placement Blocking: Blocks mace storage via armor stands or item display entities
- Player Tracking: Monitors maces across online/offline players and dropped items
- Auto-Cleanup: Automatically frees slots when maces are destroyed or lost to void
- Heavy Core Control: Prevents automation abuse by blocking heavy cores in auto-crafters
- World Restrictions: Configure crafting permissions per world
- Permission System: Full admin bypass and control permissions
- Auto-Save: Configurable automatic data saving
- Notifications: Optional server broadcast messages for mace events

## Installation

1. Download the latest `.jar` from [SpigotMC](https://www.spigotmc.org/resources/macecontrol-1-21-limit-maces-prevent-storage.125139/)
2. Drop it into your `/plugins` folder
3. Restart your server
4. Configure settings in `plugins/MaceControl/config.yml`
5. Run `/macereload` 

## Commands & Permissions

| Command        | Description                              |
|----------------|------------------------------------------|
| `/mace`        | See who currently holds maces (cooldown) |
| `/droppedmace` | Admin command to view dropped maces      |
| `/macereload`  | Admin command to reload plugin config    |
## ⚙️ Configuration

```yaml
# Maximum number of Maces allowed on server
max-mace-count: 5

# Permissions
bypass-permission: "macecontrol.bypass"
admin-permission: "macecontrol.admin"

# Auto-save interval in minutes
auto-save-interval-minutes: 5

# Command cooldown in seconds
mace-command-cooldown: 5

# World restrictions
worlds:
  allowed-craft-worlds: []  # Empty = all worlds allowed
  restricted-worlds: []     # Worlds where crafting is blocked

# Item restrictions
restrictions:
  block-containers: true           # Block storing in chests, etc.
  allow-ender-chest: false        # Allow ender chest storage
  allow-shulker-boxes: false      # Allow shulker box storage
  block-item-frames: true         # Block placing on item frames
  block-armor-stands: true        # Block placing on armor stands
  block-heavy-core-autocrafter: true  # Block heavy core automation
  block-flower-pots: true       # Block placing into flower pots

# Messages
messages:
  broadcast-destruction: true     # Announce when Mace is destroyed
  broadcast-craft: true          # Announce when Mace is crafted

