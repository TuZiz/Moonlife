# Moonlife

Moonlife 是一个面向 Paper / Folia / Spigot 的月相、日相、昼夜生态玩法插件。插件负责生态调度、规则判断、性能保护和玩家反馈；怪物表现完全交给 MythicMobs 定义。

## 环境

- Minecraft / Paper API: `26.1.2`
- Kotlin JVM
- JDK: `25`
- 构建: Gradle Kotlin DSL
- 兼容: Paper / Folia / Spigot
- 强依赖: 无
- 软依赖: MythicMobs, PlaceholderAPI, WorldGuard

运行端注意：`plugin.yml` 的 `api-version` 不能高于服务器实际版本。当前打包声明为 `26.1.1`，可直接加载在 `paper-26.1.1`；如果服务器升级到 `26.1.2`，也可以再把声明同步改回 `26.1.2`。

## 设计边界

- Moonlife 不使用 NMS。
- Moonlife 不做跨服同步，不接 Redis / MySQL / PostgreSQL。
- Monster 生态刷新默认完全依靠 MythicMobs。
- `config.yml` 中 `spawn.mythic-mobs-only` 默认为 `true`，误配的 `VANILLA` 刷怪规则会被跳过。
- Moonlife 不替代 MythicMobs，不定义完整怪物技能系统。
- MythicMobs 负责怪物实体、技能、AI、装备、掉落表现。
- Moonlife 负责月相、日相、天气、群系、地形、荒野、冷却、权重、限速和刷新触发条件。

## 已落地的 20 个功能

### 玩法功能 8 项

| 功能 | 说明 |
| --- | --- |
| MythicMobs 生态刷新 | 怪物完全依靠 MythicMobs，Moonlife 只按月相、日相、天气、群系、荒野和限速触发刷新 |
| 生态危险等级 | 按当前位置刷怪权重、天气、日相、月相、热点、活动倍率和玩家保护计算 `SAFE` / `WATCH` / `DANGER` / `NIGHTMARE` |
| 区域生态热点 | `altars.yml` 定义热点，`moon-phases/` 与 `solar-phases/` 决定在哪些相位启用 |
| 月相悬赏 | 击杀指定 MythicMob 后发放经验、生态材料并记录完成状态 |
| 生态图鉴 | 玩家击杀生态目标后用 scoreboard tag 解锁图鉴条目，无数据库依赖 |
| 生态材料 | 默认提供月露种子、影尘、雷骨，可通过作物收获和悬赏获得 |
| 月相祭坛 | 指定月相和日相下，使用材料右键祭坛方块启动临时生态事件 |
| 临时生态事件 | 管理员或祭坛可启动限时活动倍率，影响 MythicMobs 生态刷新数量和 PAPI 显示 |

### 运维功能 6 项

| 功能 | 命令 |
| --- | --- |
| 未来月相日历 | `/ecology calendar [world]` |
| 当前位置规则解释 | `/ecology inspect` |
| MythicMobs 与生态规则校验 | `/ecology validate` |
| 实时危险等级 BossBar | `/ecology bossbar` |
| 规则触发统计 | `/ecology stats` |
| 世界配置模板建议 | `/ecology template [name]` |

### 技术功能 6 项

| 功能 | 说明 |
| --- | --- |
| Moonlife API | `MoonlifeProvider.api()` 可读取月相、日相、危险等级、活跃功能和活动 |
| PlaceholderAPI 扩展 | 新增危险等级、热点、活动、保护状态、悬赏、图鉴、生态材料等变量 |
| 玩家保护 | 新玩家在线时间保护和出生点半径保护会跳过生态刷怪 |
| MythicMobs-only 防线 | `spawn.mythic-mobs-only=true` 时原版刷怪规则会被校验提示并跳过 |
| 性能跳过统计 | TPS/MSPT 保护触发时记录 `performance_guard` 统计，便于排查 |
| 独立功能配置 | 根目录定义玩法模块，相位目录定义启用关系，启动和重载时一次性解析进内存 |

## 默认 MythicMobs 怪物

默认 `monsters.yml` 使用以下 MythicMobs 内部怪物名：

| 规则 | MythicMobs ID | 触发环境 |
| --- | --- | --- |
| `fullmoon_zombie_knight` | `FullMoonZombieKnight` | 满月夜晚 / 午夜 |
| `newmoon_shadow_beast` | `ShadowBeast` | 新月午夜，黑森林 / 沼泽 / 红树林沼泽 |
| `thunder_night_raider` | `StormboneRaider` | 雷雨夜晚 / 午夜 |

你需要在 MythicMobs 中创建这些 mob，或者把 `mythic-mob-id` 改成你已有的内部怪物名。

## 默认月相与日相功能

下面描述的是当前默认配置直接启用的功能，不是理论能力上限。

### 默认月相功能

| 月相 | 默认功能 |
| --- | --- |
| `NEW_MOON` 新月 | 在 `MIDNIGHT` 且满足群系与环境条件时刷新 `ShadowBeast`；黑森林、沼泽、红树林沼泽会成为暗影热点；成熟作物有概率产出影尘；击杀 `ShadowBeast` 解锁图鉴和悬赏 |
| `FULL_MOON` 满月 | 在 `NIGHT` / `MIDNIGHT` 刷新 `FullMoonZombieKnight`；下界 `NETHER_WART` 获得轻微成长和收获加成；成熟作物有概率产出月露种子；可使用满月祭坛启动满月狂潮 |
| `WAXING_CRESCENT` 娥眉月 | 默认配置中没有额外功能，预留给后续规则扩展 |
| `FIRST_QUARTER` 上弦月 | 默认配置中没有额外功能，预留给后续规则扩展 |
| `WAXING_GIBBOUS` 盈凸月 | 默认配置中没有额外功能，预留给后续规则扩展 |
| `WANING_GIBBOUS` 亏凸月 | 默认配置中没有额外功能，预留给后续规则扩展 |
| `LAST_QUARTER` 下弦月 | 默认配置中没有额外功能，预留给后续规则扩展 |
| `WANING_CRESCENT` 残月 | 默认配置中没有额外功能，预留给后续规则扩展 |

### 默认日相功能

| 日相 | 默认功能 |
| --- | --- |
| `DAWN` 黎明 | 默认配置中没有独立功能，预留给后续规则扩展 |
| `DAY` 白昼 | 晴天时农作物成长加速，影响小麦、胡萝卜、马铃薯、甜菜、南瓜茎、西瓜茎、地狱疣 |
| `DUSK` 黄昏 | 玩家在野外会获得默认黄昏增益：采集加速、轻微移速提升、经验倍率提升、掉落倍率提升 |
| `NIGHT` 夜晚 | 满月夜会刷新 `FullMoonZombieKnight`；雷雨夜会刷新 `StormboneRaider`，同时玩家在野外承受更高危险并获得更高经验/掉落收益 |
| `MIDNIGHT` 午夜 | 新月午夜会刷新 `ShadowBeast`；满月午夜会刷新 `FullMoonZombieKnight`；雷雨午夜同样会触发 `StormboneRaider` 和危险状态规则 |

### 默认天气联动

| 天气 | 默认功能 |
| --- | --- |
| `CLEAR` 晴天 | 配合 `DAY` 触发作物成长加速 |
| `RAIN` 雨天 | 配合 `DUSK` 仍可触发黄昏采集增益 |
| `THUNDER` 雷暴 | 配合 `NIGHT` / `MIDNIGHT` 提高野外危险度，刷新 `StormboneRaider`，并给玩家更高风险收益状态 |

### 默认功能速览

- 农业向：`DAY + CLEAR`
- 采集向：`DUSK + CLEAR/RAIN`
- 危险探索向：`NIGHT/MIDNIGHT + THUNDER`
- 满月精英怪：`FULL_MOON + NIGHT/MIDNIGHT`
- 新月暗影怪：`NEW_MOON + MIDNIGHT`

## 安装

1. 将 `build/libs/Moonlife-1.0-SNAPSHOT.jar` 放入服务器 `plugins` 目录。
2. 安装 MythicMobs。
3. 可选安装 PlaceholderAPI 和 WorldGuard。
4. 启动服务器生成默认配置。
5. 修改 `plugins/Moonlife/monsters.yml` 中的 `mythic-mob-id`。
6. 执行 `/ecology reload` 热重载配置。

## 构建

当前工程使用 Gradle Kotlin DSL：

```powershell
.\gradlew.bat build --no-daemon
```

如果没有全局 Gradle，可以使用本机 Gradle wrapper 分发或 IDE 的 Gradle 面板执行 `build`。

## 命令

| 命令 | 说明 | 权限 |
| --- | --- | --- |
| `/lunarphase` | 查看当前世界月相 | `ecology.info` |
| `/solarphase` | 查看当前世界日相 | `ecology.info` |
| `/ecology` | 查看帮助菜单 | `ecology.info` |
| `/ecology info` | 查看世界、月相、日相、天气 | `ecology.info` |
| `/ecology preview` | 预览当前位置命中的刷怪规则 | `ecology.preview` |
| `/ecology reload` | 校验并热重载配置 | `ecology.reload` |
| `/ecology debug` | 查看规则缓存数量 | `ecology.debug` |
| `/ecology setmoon <phase> [world]` | 手动覆盖月相 | `ecology.setphase` |
| `/ecology setsolar <phase> [world]` | 手动覆盖日相 | `ecology.setphase` |
| `/ecology testspawn` | 在附近执行一次测试刷新 | `ecology.debug` |
| `/ecology testbuff` | 对自己执行一次 Buff 规则测试 | `ecology.debug` |
| `/ecology calendar [world]` | 查看未来月相与命中功能日历 | `ecology.info` |
| `/ecology inspect` | 解释当前位置命中的刷怪、作物、状态、热点和危险度 | `ecology.debug` |
| `/ecology validate` | 校验 MythicMobs 可用性、怪物索引和规则配置 | `ecology.debug` |
| `/ecology bossbar` | 开关实时危险等级 BossBar | `ecology.debug` |
| `/ecology event start <id> [minutes] [multiplier]` | 启动临时生态事件 | `ecology.debug` |
| `/ecology bounty` | 查看月相悬赏 | `ecology.info` |
| `/ecology codex` | 查看已解锁生态图鉴 | `ecology.info` |
| `/ecology materials` | 查看生态材料来源 | `ecology.info` |
| `/ecology template [name]` | 查看世界配置模板建议 | `ecology.preview` |
| `/ecology stats` | 查看生态规则触发与跳过统计 | `ecology.debug` |

## 权限

| 权限 | 默认 | 说明 |
| --- | --- | --- |
| `ecology.admin` | OP | 管理员总权限 |
| `ecology.reload` | OP | 允许重载配置 |
| `ecology.debug` | OP | 允许调试和测试命令 |
| `ecology.setphase` | OP | 允许覆盖月相 / 日相 |
| `ecology.info` | 所有人 | 允许查看生态信息 |
| `ecology.preview` | OP | 允许预览生态规则 |

## PlaceholderAPI 变量

变量前缀为 `%moonlife_<变量>%`。

### 基础状态

| 变量 | 输出 |
| --- | --- |
| `%moonlife_world%` | 当前世界名 |
| `%moonlife_weather%` | 当前天气：`CLEAR` / `RAIN` / `THUNDER` |
| `%moonlife_moon%` | 当前月相枚举名 |
| `%moonlife_moon_phase%` | 当前月相枚举名 |
| `%moonlife_moon_localized%` | 当前月相本地化显示名 |
| `%moonlife_moon_display%` | 当前月相本地化显示名 |
| `%moonlife_moon_index%` | 当前月相序号，1 到 8 |
| `%moonlife_solar%` | 当前日相枚举名 |
| `%moonlife_solar_phase%` | 当前日相枚举名 |
| `%moonlife_solar_localized%` | 当前日相本地化显示名 |
| `%moonlife_solar_display%` | 当前日相本地化显示名 |
| `%moonlife_solar_index%` | 当前日相序号，1 到 5 |
| `%moonlife_summary%` | `月相:日相:天气` 简短摘要 |
| `%moonlife_phase_summary%` | 本地化月相 / 日相 / 天气摘要 |

### 刷怪功能

这些变量基于玩家当前位置，只读预览当前可命中的野外刷新规则，不会触发刷怪。

| 变量 | 输出 |
| --- | --- |
| `%moonlife_spawn_rules%` | 当前命中的刷怪规则 id 列表 |
| `%moonlife_active_spawn_rules%` | 当前命中的刷怪规则 id 列表 |
| `%moonlife_spawn_count%` | 当前命中的刷怪规则数量 |
| `%moonlife_active_spawn_count%` | 当前命中的刷怪规则数量 |
| `%moonlife_spawn_targets%` | 当前命中的 MythicMobs 怪物 ID 列表 |
| `%moonlife_spawn_feature%` | 当前刷怪功能摘要，例如 `刷怪:ShadowBeast x1-1 权重8` |

### 作物功能

这些变量基于玩家当前位置预览作物规则的环境部分；实际成长仍由作物事件触发。

| 变量 | 输出 |
| --- | --- |
| `%moonlife_crop_rules%` | 当前命中的作物规则 id 列表 |
| `%moonlife_active_crop_rules%` | 当前命中的作物规则 id 列表 |
| `%moonlife_crop_count%` | 当前命中的作物规则数量 |
| `%moonlife_active_crop_count%` | 当前命中的作物规则数量 |
| `%moonlife_crop_feature%` | 当前作物功能摘要，例如 `作物:sunny_day_growth 成长x1.35 收获+6.00% 变异+0.50%` |

### 玩家状态功能

这些变量基于玩家当前位置和状态预览当前 Buff 计划，不会强制应用新 Buff。

| 变量 | 输出 |
| --- | --- |
| `%moonlife_buff_rules%` | 当前命中的玩家状态规则 id 列表 |
| `%moonlife_active_buff_rules%` | 当前命中的玩家状态规则 id 列表 |
| `%moonlife_buff_count%` | 当前命中的玩家状态规则数量 |
| `%moonlife_active_buff_count%` | 当前命中的玩家状态规则数量 |
| `%moonlife_buff_feature%` | 当前玩家状态摘要，例如 `状态:dusk_forager 经验x1.15 掉落x1.12 伤害x1.00 承伤x1.00` |
| `%moonlife_features%` | 刷怪、作物、玩家状态的综合摘要 |
| `%moonlife_phase_features%` | `%moonlife_features%` 的别名 |

### 扩展功能变量

| 变量 | 输出 |
| --- | --- |
| `%moonlife_danger%` | 当前生态危险等级 |
| `%moonlife_danger_level%` | `%moonlife_danger%` 的别名 |
| `%moonlife_danger_score%` | 当前生态危险分，范围 0 到 120 |
| `%moonlife_hotspot%` | 当前命中的生态热点 id，没有则为 `-` |
| `%moonlife_hotspot_multiplier%` | 当前热点倍率，没有则为 `1.00` |
| `%moonlife_active_event%` | 当前最高倍率临时生态事件 id，没有则为 `-` |
| `%moonlife_event_multiplier%` | 当前临时生态事件倍率 |
| `%moonlife_event_seconds%` | 当前临时生态事件剩余秒数 |
| `%moonlife_protected%` | 玩家是否处于新手或出生点生态保护 |
| `%moonlife_bounty_count%` | 当前可查看的悬赏数量 |
| `%moonlife_codex_count%` | 玩家已解锁生态图鉴数量 |
| `%moonlife_materials%` | 已配置生态材料与来源摘要 |

## 插件 API

其他插件可以通过 `MoonlifeProvider.api()` 读取 Moonlife 的运行状态：

```kotlin
val api = MoonlifeProvider.api()
val moon = api?.moonPhase(player.world)
val solar = api?.solarPhase(player.world)
val danger = api?.dangerLevel(player)
val features = api?.activeFeatures(player)
```

## 配置文件

新配置拆成“玩法定义”和“相位启用关系”两层，改月相/日相玩法时不用再翻多个大文件。

| 路径 | 说明 |
| --- | --- |
| `config.yml` | 运行时开关、性能保护、荒野判断、刷新节流 |
| `monsters.yml` | 只定义怪物规则、MythicMobs ID、距离、权重、冷却、地形条件 |
| `altars.yml` | 只定义祭坛、生态材料、悬赏、热点、临时事件 |
| `crops.yml` | 只定义作物成长、收获、变异规则 |
| `buffs.yml` | 只定义玩家药水、属性、伤害、经验、掉落规则 |
| `moon-phases/*.yml` | 每个月相启用哪些怪物、祭坛、作物、Buff、热点 |
| `solar-phases/*.yml` | 每个日相的 tick 区间，以及启用哪些怪物、祭坛、作物、Buff、热点 |
| `lang/zh_cn.yml` | 中文语言文件，优先于旧 `messages.yml` 加载 |

例如 `moon-phases/新月.yml`：

```yaml
functions:
  monsters:
    - newmoon_shadow_beast
  altars: []
  crops: []
  buffs: []
  hotspots:
    - newmoon_shadow_hotspot
```

例如 `solar-phases/午夜.yml`：

```yaml
time:
  start: 18000
  end: 23000
functions:
  monsters:
    - newmoon_shadow_beast
    - fullmoon_zombie_knight
  buffs:
    - thunder_night_danger
```

兼容说明：旧的 `spawn-rules.yml`、`crop-rules.yml`、`buff-rules.yml`、`features.yml`、`moon-phases.yml`、`solar-phases.yml` 仍可作为兜底读取；只要新文件存在，Moonlife 会优先使用新结构。

## 性能策略

- 配置只在启动和 `/ecology reload` 时读取。
- 规则解析后进入不可变内存模型。
- 刷怪采用玩家附近周期抽样，不做全世界扫描。
- 刷怪规则做静态候选缓存、世界限速、区块限速、规则冷却和 TPS/MSPT 保护。
- 荒野判断做 chunk 级 TTL 缓存。
- 作物系统只响应成长、骨粉、收获事件。
- Buff 系统节流刷新玩家状态并缓存计划，避免频繁重复上药水。
- Folia 下实体操作走 Entity Scheduler，方块和区域操作走 Region Scheduler，全局逻辑走 Global Scheduler。

## 热重载

执行：

```text
/ecology reload
```

重载流程是先读取和校验配置，再构建新内存模型，最后原子替换；失败时保留上一份可用配置。

## MythicMobs 示例

下面只是最小占位示例，正式服建议在 MythicMobs 中完整配置技能、掉落、装备和 AI。

```yaml
FullMoonZombieKnight:
  Type: ZOMBIE
  Display: '&dFull Moon Zombie Knight'
  Health: 60
  Damage: 8

ShadowBeast:
  Type: WOLF
  Display: '&8Shadow Beast'
  Health: 80
  Damage: 10

StormboneRaider:
  Type: SKELETON
  Display: '&bStormbone Raider'
  Health: 55
  Damage: 7
```

## 注意事项

- 如果没有安装 MythicMobs，Moonlife 会正常启动，但默认刷怪规则会被跳过。
- 如果没有安装 PlaceholderAPI，占位符扩展会自动跳过。
- 如果没有安装 WorldGuard，荒野判断会按未接入保护插件处理。
