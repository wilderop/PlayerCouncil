# PlayerCouncil

Paper plugin that ranks players by real activity metrics and forms a **Player Council** with democratic voting powers.

## Performance model
- Activity is sampled **only on login and logout** from vanilla player statistics.
- All SQLite work runs on a **single background DB thread** (write queue).
- Ranking is fully async; council membership is applied on the main thread.
- Snapshots older than **30 days** are pruned automatically.

## Metrics (equal weight by default)
- Playtime (`PLAY_ONE_MINUTE`)
- Walk distance (`WALK_ONE_CM`)
- Elytra distance (`AVIATE_ONE_CM`)
- Mob kills (`MOB_KILLS`)

## Ban ladder (automatic)
- `/propose ban <player>` / `/propose unban <player>`
- System picks BAN/PARDON/REBAN/REPARDON and vote thresholds (1/2/4/8) from stored ladder stage
- Sitting council members cannot be removed with a 1-vote ban

## Build
```bash
mvn clean package
```
Requires Java 21+.

## License
MIT
