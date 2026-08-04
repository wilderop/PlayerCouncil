# PlayerCouncil

Paper plugin: activity-based Player Council with voting for bans, gamerules, and plugin toggles.

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

## Build
```bash
mvn clean package
```
Requires Java 21+.

## License
MIT
