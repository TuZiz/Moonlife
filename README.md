# Moonlife

Moonlife 是一个面向 Paper / Folia / Spigot 的月相、日相、昼夜生态玩法插件。默认配置采用“原版轻生态”：原版怪物、作物成长、天气收益和玩家状态轻量联动，不强制依赖 MythicMobs 或祭坛玩法。

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
- Monster 生态刷新默认使用 Bukkit / Paper 原版实体。
- `config.yml` 中 `spawn.mythic-mobs-only` 默认为 `false`，可以直接运行原版轻生态。
- MythicMobs 仍作为软依赖保留，需要自定义怪时再把规则切换为 `MYTHIC_MOB`。
- Moonlife 不替代 MythicMobs，不定义完整怪物技能系统。
- Moonlife 负责月相、日相、天气、群系、地形、荒野、冷却、权重、限速和刷新触发条件。

## 已落地的 20 个功能

### 玩法功能 8 项

| 功能 | 说明 |
| --- | --- |
| 原版轻生态刷新 | 默认使用原版僵尸、蜘蛛、骷髅，Moonlife 按月相、日相、天气、荒野和限速触发刷新 |
| 生态危险等级 | 按当前位置刷怪权重、天气、日相、月相、热点、活动倍率和玩家保护计算 `SAFE` / `WATCH` / `DANGER` / `NIGHTMARE` |
| 昼夜精简提示 | 默认每个白昼或夜晚只发送一条聊天和一条 ActionBar |
| 晴天作物成长 | 白昼晴天提高作物成长与额外收获概率 |
| 黄昏采集收益 | 黄昏野外玩家获得轻微采集、移动、经验和掉落收益 |
| 雷雨高危收益 | 雷雨夜提高荒野危险度，同时提高战斗收益 |
| 可选 MythicMobs 接入 | 需要自定义怪时可把单条规则切换为 `MYTHIC_MOB`，未安装时不影响原版轻生态 |
| 可选扩展槽 | 祭坛、悬赏、热点、临时事件仍保留能力，但默认配置留空 |

### 运维功能 6 项

| 功能 | 命令 |
| --- | --- |
| 未来月相日历 | `/ecology calendar [world]` |
| 当前位置规则解释 | `/ecology inspect` |
| 生态规则校验 | `/ecology validate` |
| 实时危险等级 BossBar | `/ecology bossbar` |
| 规则触发统计 | `/ecology stats` |
| 世界配置模板建议 | `/ecology template [name]` |

### 技术功能 6 项

| 功能 | 说明 |
| --- | --- |
| Moonlife API | `MoonlifeProvider.api()` 可读取月相、日相、危险等级、活跃功能和活动 |
| PlaceholderAPI 扩展 | 新增危险等级、热点、活动、保护状态、悬赏、图鉴、生态材料等变量 |
| 玩家保护 | 新玩家在线时间保护和出生点半径保护会跳过生态刷怪 |
| MythicMobs-only 防线 | 需要纯 MythicMobs 服时可启用 `spawn.mythic-mobs-only=true` |
| 性能跳过统计 | TPS/MSPT 保护触发时记录 `performance_guard` 统计，便于排查 |
| 独立功能配置 | 根目录定义玩法模块，相位目录定义启用关系，启动和重载时一次性解析进内存 |

## 默认原版轻生态怪物

默认 `monsters.yml` 不依赖 MythicMobs，直接使用原版实体：

| 中文显示名 | 规则 ID | 原版实体 | 触发环境 |
| --- | --- | --- | --- |
| 满月僵尸群 | `fullmoon_zombie_pack` | `ZOMBIE` | 满月夜晚 / 午夜 |
| 新月夜蛛 | `newmoon_night_spider` | `SPIDER` | 新月午夜 |
| 雷雨骷髅巡游 | `thunder_skeleton_patrol` | `SKELETON` | 雷雨夜晚 / 午夜 |

需要自定义怪时，把对应规则的 `spawn-backend` 改成 `MYTHIC_MOB` 并填写 `mythic-mob-id` 即可。Moonlife 只负责生态条件和触发时机，不覆盖 MythicMobs 自己的怪物名字。

## 默认月相与日相功能

下面描述的是当前默认配置直接启用的功能，不是理论能力上限。

### 默认月相功能

| 月相 | 默认功能 |
| --- | --- |
| `NEW_MOON` 新月 | 在 `MIDNIGHT` 提高新月夜蛛的荒野出现概率 |
| `FULL_MOON` 满月 | 在 `NIGHT` / `MIDNIGHT` 提高满月僵尸群出现概率；下界 `NETHER_WART` 获得轻微成长和收获加成 |
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
| `NIGHT` 夜晚 | 满月夜会刷新满月僵尸群；雷雨夜会刷新雷雨骷髅巡游，同时玩家在野外承受更高危险并获得更高经验/掉落收益 |
| `MIDNIGHT` 午夜 | 静默增强新月夜蛛、满月僵尸群和雷雨骷髅巡游，不额外刷聊天 |

### 默认天气联动

| 天气 | 默认功能 |
| --- | --- |
| `CLEAR` 晴天 | 配合 `DAY` 触发作物成长加速 |
| `RAIN` 雨天 | 配合 `DUSK` 仍可触发黄昏采集增益 |
| `THUNDER` 雷暴 | 配合 `NIGHT` / `MIDNIGHT` 提高野外危险度，刷新雷雨骷髅巡游，并给玩家更高风险收益状态 |

### 默认功能速览

- 农业向：`DAY + CLEAR`
- 采集向：`DUSK + CLEAR/RAIN`
- 危险探索向：`NIGHT/MIDNIGHT + THUNDER`
- 满月原版怪：`FULL_MOON + NIGHT/MIDNIGHT`
- 新月原版怪：`NEW_MOON + MIDNIGHT`
- 提示策略：每个白昼或夜晚只发一条聊天和一条 ActionBar

## 安装

1. 将 `build/libs/Moonlife-1.0-SNAPSHOT.jar` 放入服务器 `plugins` 目录。
2. 可选安装 PlaceholderAPI 和 WorldGuard。
3. 如果需要自定义怪，再额外安装 MythicMobs。
4. 启动服务器生成默认配置。
5. 默认原版轻生态可直接使用，不需要配置 MythicMobs。
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
| `/ecology validate` | 校验怪物、作物、Buff 和扩展规则配置 | `ecology.debug` |
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
| `%moonlife_spawn_rules%` | 当前命中的刷怪规则中文显示名列表 |
| `%moonlife_active_spawn_rules%` | 当前命中的刷怪规则中文显示名列表 |
| `%moonlife_spawn_count%` | 当前命中的刷怪规则数量 |
| `%moonlife_active_spawn_count%` | 当前命中的刷怪规则数量 |
| `%moonlife_spawn_targets%` | 当前命中的怪物目标显示名列表 |
| `%moonlife_spawn_feature%` | 当前刷怪功能摘要，例如 `刷怪:新月夜蛛 x1-1 权重12` |

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
| `monsters.yml` | 只定义怪物规则、原版实体或 MythicMobs ID、距离、权重、冷却、地形条件 |
| `altars.yml` | 可选定义祭坛、生态材料、悬赏、热点、临时事件；轻生态默认留空 |
| `crops.yml` | 只定义作物成长、收获、变异规则 |
| `buffs.yml` | 只定义玩家药水、属性、伤害、经验、掉落规则 |
| `config.yml` | 运行时开关、性能保护、荒野判断、刷新节流 |
| `moon-phases/*.yml` | 每个月相启用哪些怪物、祭坛、作物、Buff、热点 |
| `solar-phases/*.yml` | 每个日相的 tick 区间、启用规则和是否公告 |
| `lang/zh_cn.yml` | 中文语言文件，优先于旧 `messages.yml` 加载 |

例如 `moon-phases/新月.yml`：

```yaml
functions:
  monsters:
    - newmoon_night_spider
  altars: []
  crops: []
  buffs: []
  hotspots: []
```

例如 `solar-phases/午夜.yml`：

```yaml
time:
  start: 18000
  end: 23000
functions:
  monsters:
    - newmoon_night_spider
    - fullmoon_zombie_pack
    - thunder_skeleton_patrol
  buffs:
    - thunder_night_danger
messages:
  announce: false
```

兼容说明：旧的 `spawn-rules.yml`、`crop-rules.yml`、`buff-rules.yml`、`features.yml`、`moon-phases.yml`、`solar-phases.yml` 仍可作为兜底读取；旧版 `settings/config.yml` 也会作为 `config.yml` 的兜底读取。新默认包不再生成这些旧文件，只要新文件存在，Moonlife 会优先使用新结构。

## 相位提示

月相和日相切换提示现在直接写在对应相位文件里，例如 `moon-phases/新月.yml`、`solar-phases/午夜.yml`。配置支持 MiniMessage RGB，例如 `<#7DD3FC>`，实际发送时会转成 Minecraft 客户端识别的 `§x` 真彩格式。

```yaml
messages:
  announce: true
  broadcast: '<prefix><#93C5FD>夜幕降临 ✦ <world> 今夜生态：原版怪物更活跃，雷雨时风险与收益同步上升。'
  actionbar: '<#93C5FD>夜晚</#93C5FD> <#94A3B8>·</#94A3B8> <#DBEAFE>荒野危险上升</#DBEAFE> <#94A3B8>·</#94A3B8> <#E0F2FE>/ecology info</#E0F2FE>'
  features: []
```

`announce: false` 表示该相位仍会参与规则匹配，但不会发送聊天、ActionBar、Title 或 BossBar。默认只有 `白昼` 和 `夜晚` 公告，`黎明`、`黄昏`、`午夜` 静默。

## 自定义物品

祭坛消耗物、生态材料、作物额外收获都支持自定义物品配置。轻生态默认不启用祭坛，但能力仍保留。Moonlife 不使用 NMS，`nbt` 节点实际写入 Bukkit PersistentDataContainer，格式使用 `namespace:key`。

```yaml
cost-item:
  material: AMETHYST_SHARD
  display-name: "<#C4B5FD>月露种子"
  lore:
    - "<#A7F3D0>月息生态材料"
    - "<#94A3B8>来源：满月成熟作物"
  custom-model-data: 1001
  nbt:
    "moonlife:item_id": moonlit_seed
  match:
    display-name: true
    lore: false
    custom-model-data: true
    nbt: true
    name-mode: EXACT
```

作物收获的自定义掉落写在规则里的 `extra-harvest-items`，命中 `extra-harvest-chance` 后会掉落配置物品，不再复制原版掉落：

```yaml
extra-harvest-items:
  sunny_harvest_seed:
    chance: 1.0
    amount: [1, 1]
    material: WHEAT_SEEDS
    display-name: "<#86EFAC>晴辉种子"
    lore:
      - "<#A7F3D0>月息生态作物产物"
    custom-model-data: 2001
    nbt:
      "moonlife:item_id": sunny_harvest_seed
```

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

## 可选 MythicMobs 集成

默认轻生态不需要 MythicMobs。需要自定义怪时，可以把某条怪物规则改成：

```yaml
spawn-backend: MYTHIC_MOB
mythic-mob-id: YourMobId
```

如果服务器安装了 MythicMobs，Moonlife 会通过软 Hook 调用它；如果没安装且没有 `MYTHIC_MOB` 规则，启动时只输出普通说明，不会警告刷怪规则被跳过。

## 注意事项

- 默认轻生态不要求 MythicMobs，原版怪物规则可直接运行。
- 如果没有安装 PlaceholderAPI，占位符扩展会自动跳过。
- 如果没有安装 WorldGuard，荒野判断会按未接入保护插件处理。
