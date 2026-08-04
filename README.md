# PlayerCouncil

Paper plugin that ranks players by real activity metrics and forms a **Player Council** with democratic voting powers.

## Features

### Activity Ranking
- Snapshots player stats on every login and logout (low lag)
- Metrics (equal weight by default):
  - Playtime
  - Distance walked
  - Distance flown (elytra)
  - Mobs killed
  - Blocks broken
  - Blocks placed
- Weekly and monthly rankings
- Configurable minimum total playtime filter
- Tiebreakers: total hours → earliest join date
- Top N players (default 12) automatically become the Council

### Council Powers
| Action              | Votes required |
|---------------------|----------------|
| Ban a player        | 1              |
| Pardon              | 2              |
| Re-ban              | 4              |
| Re-pardon           | 8              |
| Change a gamerule   | 8              |
| Enable/disable plugin | 8            |

- Council members can only be banned via the high-threshold path
- Proposals expire after 7 days (configurable)
- Original proposer can cancel
- Fully public votes (no secret ballot)
- Discord webhook support for every proposal and vote
- Full audit log

### Plugin Toggles
- You control a whitelist of plugins the council may enable/disable
- On success → 10-minute warning → `/stop` (your auto-restart handles the rest)

## Commands

| Command | Permission | Description |
|---------|------------|-------------|
| `/council` | `playercouncil.use` | View current council members |
| `/activity [week\|month]` | `playercouncil.use` | View activity rankings |
| `/proposals` | `playercouncil.use` | List active proposals + votes + time remaining |
| `/propose <type> ...` | `playercouncil.council` | Create a proposal (with confirmation) |
| `/vote <id> <yes\|no>` | `playercouncil.council` | Vote on a proposal |
| `/cancelproposal <id>` | `playercouncil.council` | Cancel your own proposal |
| `/counciladmin ...` | `playercouncil.admin` | Reload, recalc, set size, manage whitelist, audit |

## Installation

1. Build with Maven: `mvn clean package`
2. Place the shaded jar from `target/` into your `plugins/` folder
3. Start the server once to generate `config.yml`
4. Edit `config.yml` (especially the Discord webhook and plugin whitelist)
5. Restart

## Building

Requires Java 21+.

```bash
mvn clean package
```

## License

MIT
