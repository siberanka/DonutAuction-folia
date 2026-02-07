# DonutAuctions

A DonutSMP-style `/ah` auction plugin for modern Paper/Folia servers.

## Version
- Current release: **1.0.0**
- Built artifact: `target/donutauctions-1.0.0.jar`

## Highlights
- DonutSMP-inspired auction house flow (`/ah`)
- Full GUI text customization from language files (`lang/en.yml`, `lang/tr.yml`)
- Config-based language selection (`config.yml`)
- Vault economy support
- UltimateShop integration
  - Base auction multiplier from sell price
  - Per-enchantment level multipliers
  - Optional dynamic-sale increment
  - Optional command trigger on dynamic-sale update (`%material%`, `%amount%`)
- Folia-compatible scheduling path (`folia-supported: true`)

## Compatibility
- Paper 1.21.x
- Folia 1.21.x (including 1.21.11 target)
- Java 21

## Installation
1. Install required dependency plugin:
   - Vault
2. Optional but recommended:
   - UltimateShop (for auto pricing and dynamic-sale integration)
3. Copy `donutauctions-1.0.0.jar` into your server `plugins/` folder.
4. Start the server once to generate config and language files.
5. Edit `plugins/DonutAuctions/config.yml` and language files as needed.
6. Restart server or run `/ah reload` (OP/admin).

## Commands
- `/ah` -> open auction menu
- `/ah sell <price> <amount>` -> list `<amount>` items from main hand
- `/ah my` or `/ah myitems` -> open your active listings
- `/ah transactions` or `/ah tx` -> open transaction history
- `/ah reload` -> reload plugin config/language

If `ultimateshop.force-recommended-price-when-enabled: true`, use:
- `/ah sell <amount>` -> price is auto-forced from UltimateShop.

## Permissions
- `donutauctions.command.ah` (default: true)
- `donutauctions.admin.reload` (default: op)

## Localization
All GUI titles, button names, and messages are language-file driven.

- `plugins/DonutAuctions/lang/en.yml`
- `plugins/DonutAuctions/lang/tr.yml`

Select active language from:

```yml
language: en
```

## Pricing Model (UltimateShop)
Default formula:

1. `plain_unit_value = unit_sell_price * base-price-multiplier`
2. For each enchantment: `unit_bonus = plain_unit_value * enchant_level_multiplier`
3. `unit_total = plain_unit_value + sum(all unit bonuses)`
4. `final_price = unit_total * amount`

Each enchantment bonus is calculated independently from the plain value.

## Dynamic Sale Command Trigger
Optional console command dispatch when an auction sale increments dynamic sale count:

```yml
ultimateshop:
  dynamic-sale-command:
    enabled: true
    commands:
      - "broadcast Sold material: %material% (x%amount%)"
```

## Security & Stability
The project includes a dedicated hardening report:

- `SECURITY_REVIEW.md`

It covers anti-dupe strategy, rollback behavior, lifecycle safety, persistence durability, and test matrix recommendations.

## Build From Source
```bash
mvn clean package
```

Output jar:
- `target/donutauctions-1.0.0.jar`

## Project Coordinates
- Group: `com.siberanka`
- Artifact: `donutauctions`
- Package root: `com.siberanka.donutauctions`

## License
No license file is currently included. Add a `LICENSE` file before publishing if you want explicit usage terms.
