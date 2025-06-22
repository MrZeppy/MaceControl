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
# === CORE SETTINGS ===
max-mace-count: 5
mace-command-cooldown: 5 # in seconds

# === PERMISSIONS ===
# Who can bypass mace limits and restrictions
bypass-permission: "macecontrol.bypass"
admin-permission: "macecontrol.admin"

# === RESTRICTIONS ===
restrictions:
  # Prevent maces from being put in containers
  block-containers: true

  # Prevent maces from being put on armor stands
  block-armor-stands: true

  # Prevent maces from being put in item frames
  block-item-frames: true

  # Prevent maces from being put in bundles
  block-bundles: true

  # Prevent heavy cores from being put in auto crafters
  block-heavy-core-autocrafter: true

  # Allow maces in ender chests (since they're player-specific)
  allow-ender-chest: false

  # Allow maces in shulker boxes
  allow-shulker-boxes: false

# === WORLD SETTINGS ===
worlds:
  # Worlds where mace crafting is allowed (empty = all worlds)
  allowed-craft-worlds: []

  # Worlds where mace restrictions apply (empty = all worlds)
  restricted-worlds: []

# === NOTIFICATIONS ===
messages:
  # Broadcast when a mace is destroyed/lost
  broadcast-destruction: true

  # Notify when someone crafts a mace
  broadcast-craft: true

