# ⚔️ MaceControl – Limit Maces & Prevent Storage | Minecraft Plugin (1.21+)

MaceControl is a lightweight Minecraft plugin that limits the number of maces allowed on your server and prevents players from storing maces in containers like chests, bundles, and item frames. It also prevents mobs from picking up maces and tracks all maces across online/offline players and dropped items.

## 🔧 Features

- Limits total maces to 5 by default (configurable)
- Prevents mace storage in containers, bundles, and item frames
- Prevents mobs from picking up maces
- Tracks maces held by online/offline players and dropped on the ground
- Automatically frees slots when maces are destroyed or removed

## 🚀 Installation

1. Download the latest `.jar` from [SpigotMC](https://www.spigotmc.org/resources/macecontrol-1-21-limit-maces-prevent-storage.125139/)
2. Drop it into your `/plugins` folder
3. Restart your server
4. Configure settings in `plugins/MaceControl/config.yml`

## 📜 Commands & Permissions

| Command        | Description                                |
|----------------|--------------------------------------------|
| `/mace`        | See who currently holds maces (cooldown)  |
| `/droppedmace` | Admin command to view dropped maces        |

## ⚙️ Configuration

```yaml
max-mace-count: 5
mace-command-cooldown: 5
