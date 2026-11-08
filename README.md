# DSMPTitle

[![Paper](https://img.shields.io/badge/Paper-1.21.11-blue)](https://papermc.io/)
[![Java](https://img.shields.io/badge/Java-21-orange)]()
[![Dependency](https://img.shields.io/badge/Depend-Luck%20Perms-green)]()
[![Dependency](https://img.shields.io/badge/Dependency-Vault-lightgrey)]()
[![License](https://img.shields.io/badge/License-AGPL--3.0-blue)](https://www.gnu.org/licenses/agpl-3.0.html)
[![GitHub](https://img.shields.io/badge/GitHub-dydaozhu%2FDsmpTitle-181717?logo=github&logoColor=white)](https://github.com/dydaozhu/DsmpTitle)
[![Modrinth](https://img.shields.io/badge/Modrinth-Download-00AF5C?logo=modrinth&logoColor=white)](https://modrinth.com/plugin/dsmptitle)

DSMPTitle is a custom title management plugin for Paper servers. Players can purchase and manage their own custom titles, while administrators can grant, equip, remove and manage titles for players.

Each title stores its text, color and priority independently. Title prefixes are stored through LuckPerms PrefixNodes, allowing titles to be displayed in chat and the player tab list.

## Features

- Custom title purchasing system
- Multiple payment methods (economy, experience levels or items)
- LuckPerms prefix integration
- Persistent title storage
- Title management GUI with pagination
- Title switching
- Administrative title management
- Dedicated title equip command (`/dt wear`)
- Title selection by name or by index
- Configurable title colors and prefix formatting
- Multi-language support
- Configurable banned words
- Configurable title length and input timeout
- Group-based titles
- Automatic configuration and language file updates

## Required Dependencies

### LuckPerms

**Required**

DSMPTitle uses LuckPerms to store and manage title prefixes. The plugin will disable itself if the LuckPerms API is not available.

### Vault

**Optional**

Vault is only required when using the `economy` payment method.

If an item or experience payment method is configured, Vault is not required.

### Economy Plugin

When using the Vault economy payment method, you also need an economy plugin compatible with Vault.

## Commands

### Player Commands

| Command | Description |
|---|---|
| `/dt buy` | Purchase a custom title |
| `/dt cancel` | Cancel the current title creation process |
| `/dt gui` | Open your title storage |
| `/dt menu` | Open your title storage |
| `/dt clear` | Clear your equipped title |

### Administrative Commands

| Command | Description |
|---|---|
| `/dt gui <player>` | Open another player's title storage |
| `/dt menu <player>` | Open another player's title storage |
| `/dt clear <player>` | Clear another player's equipped title |
| `/dt set <player> <title> <color> <weight>` | Grant a title to a player |
| `/dt wear <player> <title\|index>` | Equip a title for a player |
| `/dt remove <player> <title\|index>` | Remove a title from a player |
| `/dt reload` | Reload all plugin configuration |
| `/dt reload data` | Reload title data |
| `/dt reload config` | Reload the main configuration |
| `/dt reload lang` | Reload language files |
| `/dt reload group` | Reload group title configuration |

## Permissions

### Player Permissions

- `dsmptitle.buy` - Purchase custom titles
- `dsmptitle.cancel` - Cancel the current title creation process
- `dsmptitle.clear` - Clear your equipped title
- `dsmptitle.menu` - Open your title storage

### Administrative Permissions

- `dsmptitle.clear.other` - Clear another player's equipped title
- `dsmptitle.menu.other` - Open another player's title storage
- `dsmptitle.reload` - Reload plugin configuration
- `dsmptitle.reload.data` - Reload title data
- `dsmptitle.reload.config` - Reload the main configuration
- `dsmptitle.reload.lang` - Reload language files
- `dsmptitle.reload.group` - Reload group title configuration
- `dsmptitle.remove` - Remove titles from players
- `dsmptitle.set` - Grant titles to players
- `dsmptitle.wear` - Equip titles for players

### Wildcard Permission

- `dsmptitle.*` - Access all DSMPTitle permissions

Player permissions default to everyone, administrative permissions default to operators.

## Title Names and Indexes

`/dt wear` and `/dt remove` accept either a title text or an index:

- A plain number that is within range is treated as an index (starting at `0`).
- Anything else is matched against the title text, case-insensitively.
- If the player owns two or more titles with the same text, the command is cancelled and an index must be used instead.

Examples:

```text
/dt wear Alex 2          # equip by index
/dt wear Alex Builder    # equip by title text
/dt remove Alex Builder  # remove by title text
```

## Configuration

The main configuration file is:

```text
plugins/DSMPTitle/config.yml
```

Important options include:

- `price` - Purchase price
- `use-item` - Payment method
- `prefix-weight` - Default priority for purchased titles
- `default-buy-title-color` - Default color for newly purchased titles
- `title-prefix-wrap` - Text placed before a title
- `title-suffix-wrap` - Text placed after a title
- `separator-color` - Color applied after the title
- `enable-chat-format` - Whether DSMPTitle formats chat messages
- `default-chat` - Chat format using `%title%`
- `min-char` - Minimum title length
- `max-char` - Maximum title length
- `wait-seconds` - Maximum time allowed for entering a title
- `language` - Language file to use
- `gui` - Title GUI settings
- `banned-words` - Forbidden words for title creation
- `config-version` - Configuration file version (do not edit manually)

YAML indentation must use spaces. Do not use tab characters.

## Configuration Updates

`config.yml` and the language files carry a version marker at the end of the file (`config-version` and `language-version`).

On startup, and on `/dt reload`, DSMPTitle compares the version found in the existing file with the version bundled in the plugin:

- **Older file** — missing keys (with their comments) are added, the version marker is updated, and every existing value is kept. For example, if the old file contains `language: en_US`, it is still `en_US` after the update.
- **Same version** — nothing is written.
- **Newer file** — a warning is logged and the file is left untouched.

Existing keys, custom values and comments are preserved. To force a clean regeneration instead, delete the file and restart the server.

## Payment Methods

DSMPTitle supports multiple payment methods through `use-item`.

### Economy

```yaml
price: 60000
use-item: "economy"
```

The configured economy amount is deducted through Vault.

Vault and a Vault-compatible economy plugin are required for this method.

### Experience

```yaml
price: 10
use-item: "xp"
```

The player must have at least 10 experience levels to purchase the title.

The configured `price` represents the number of experience levels to deduct.

Vault is not required.

### Item

```yaml
price: 32
use-item: "acacia_log"
```

The player must have 32 `acacia_log` items to purchase the title.

The configured `price` represents the number of items to consume.

Vault is not required.

## Language Files

Language files are stored in:

```text
plugins/DSMPTitle/lang/
```

Available language files include:

- `zh_CN.yml`
- `en_US.yml`

The language is selected through:

```yaml
language: zh_CN
```

The currency name can be configured in the language file:

```yaml
currency-name: "金币"
```

English:

```yaml
currency-name: "coin"
```

Plugin messages can use `%prefix%`, `%currency%` and other supported placeholders.

Example:

```yaml
success: "%prefix%&a自定义称号购买成功, 已自动装备新称号"
```

Each language file also ends with a `language-version` marker used by the automatic update process described above.

## Color Format

DSMPTitle supports Minecraft legacy `&` color codes.

Examples:

```text
&aGreen
&6Gold
&x&F&F&0&0&0&0Custom Color
```

Title colors can also use MiniMessage formatting where supported by the title configuration.

Example:

```text
<rainbow>
```

## Title GUI

The title storage GUI allows players to:

- View purchased titles
- Switch between owned titles
- View title information
- View purchase time
- Navigate between pages
- See an empty-title placeholder when no titles are owned

Administrators can open another player's title storage through the corresponding administrative permission.

## Group Titles

Group titles are configured in:

```text
plugins/DSMPTitle/group.yml
```

A group can define a title, color and priority.

Example:

```yaml
default:
  title: 萌新
  color: '&7'
  weight: 1
```

If a group does not have a configured title, no group title is applied for that group.

## Title Data

Player title data is stored persistently in:

```text
plugins/DSMPTitle/data.yml
```

The data contains player titles, colors, priorities, purchase times and active title information.

Example:

```yaml
UUID: PlayerUuid
  name: PlayerName
  titles:
    - title: "Title text1"
      color: "&6"
      weight: 610
      time: timestamp
    - title: "Title text2"
      color: "<rainbow>"
      weight: 670
      time: timestamp
  active-index: 0
```

If you manually edit the data file, use:

```text
/dt reload data
```

to reload the title data.

## Title Creation

When purchasing a title, the player must enter the title in chat within the configured time limit.

The minimum and maximum title length are controlled by:

```yaml
min-char: 2
max-char: 9
```

Titles can also be rejected when they contain configured banned words or forbidden characters.

## Notes

- LuckPerms is required for DSMPTitle to operate.
- Vault is only required for the `economy` payment method.
- Experience and item payment methods do not require Vault.
- Title indexes start at `0` and match the `active-index` field in `data.yml`.
- Title data is stored persistently in `data.yml`.
- Purchased titles use the configured purchase price and default title color.
- Each title has its own text, color and priority.
- Administrative title management is controlled by the corresponding permission nodes.
- Configuration and language files are upgraded automatically and keep your existing values.

## Source Code

The source code of DSMPTitle is published on GitHub for reference only.

👉 https://github.com/dydaozhu/DsmpTitle

## License

Copyright (C) 2025 dydaozhu

DSMPTitle is free software: you can redistribute it and/or modify it under the terms of the **GNU Affero General Public License v3.0** as published by the Free Software Foundation.

DSMPTitle is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more details.

Because DSMPTitle is licensed under the AGPL, if you run a modified version of this plugin on a publicly accessible server, you must offer the corresponding source code to the users of that server.

Full license text: https://www.gnu.org/licenses/agpl-3.0.html
