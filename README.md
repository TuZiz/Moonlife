# Moonlife

Moonlife 是一个面向 Paper / Folia / Spigot 的“原版生存增强型轻生态插件”。

它不会把服务器改成大型 RPG，也不会要求玩家每天追着任务链跑。Moonlife 的默认目标很明确：让月相、日相、天气、群系和少量热点轻微改变今天更适合做什么原版生存行为。

比如：

- 晴天白昼更适合照料农田。
- 雨天河边和海边更适合钓鱼、采集甘蔗、蘑菇和海带。
- 上弦月和下弦月更适合下矿和洞穴探索。
- 峨眉月、亏凸月更适合跑图、林地采集和野外路线。
- 新月、满月、雷雨夜会让夜晚冒险更有辨识度，但默认强度很克制。

## 技术边界

- 不使用 NMS。
- 不接 Redis / MySQL / PostgreSQL。
- 不做跨服同步。
- Kotlin JVM + Gradle Kotlin DSL。
- Paper / Folia / Spigot 兼容。
- Folia 下实体、区域、全局、异步调度都走统一调度抽象。
- 配置只在启动和 `/ecology reload` 时读取，运行时使用内存模型。
- 默认内容不依赖 MythicMobs。
- MythicMobs、PlaceholderAPI、WorldGuard 都是 softdepend。

## 默认能玩什么

默认配置已经按原版生存服直接可用，不需要先写 MythicMobs 怪物，也不需要数据库。

### 五条原版生存线

| 方向 | 默认内容 |
| --- | --- |
| 农业 | 晴天田垄、雨润根茎、盈凸月农田、满月/盈凸月成熟收获小概率产出月露种子 |
| 钓鱼 / 海洋 | 雨天河海边获得钓鱼幸运；雨天钓鱼和满月夜钓鱼小概率产出月露种子 |
| 挖矿 / 洞穴 | 上弦月地下挖掘轻微加速；下弦月地下探索承伤略降；洞穴回声热点提示下矿时机 |
| 野外采集 / 跑图 | 黄昏采集者、峨眉月旅人、亏凸月采集者、林地小径、河口湿地 |
| 夜晚冒险 | 新月夜蛛、满月僵尸群、雷雨骷髅巡游、暗沼边缘、风暴山脊 |

这些内容都是轻增强：默认倍率普遍在 `1.02` 到 `1.18` 之间，材料掉落多数低于 `5%`，不会破坏原版主循环。

## 默认月相

| 月相 | 默认定位 | 默认功能 |
| --- | --- | --- |
| 新月 | 夜间潜行 / 暗处采集 | 新月夜蛛、影尘掉落、暗沼边缘、影尘灯盏 |
| 峨眉月 | 林地采集 / 跑图 | 峨眉月旅人、林地小径 |
| 上弦月 | 洞穴 / 采矿 | 上弦月矿工、洞穴回声 |
| 盈凸月 | 农业 / 养殖 | 盈凸月农田、盈凸月田间手、月露种子、月露堆肥台 |
| 满月 | 夜战 / 钓鱼 / 收获 | 满月僵尸群、满月夜钓、月露成熟收获、满月地狱疣 |
| 亏凸月 | 野外采集 / 经验 | 亏凸月采集者、林地小径、河口湿地 |
| 下弦月 | 地下续航 | 下弦月韧性、洞穴回声 |
| 残月 | 潜行 / 残夜探索 | 残月潜行者、暗沼边缘、影尘灯盏 |

月相只是“今天做这些事情更顺一点”的提示，不是强制任务。

## 默认日相

| 日相 | 默认定位 | 默认功能 |
| --- | --- | --- |
| 黎明 | 氛围切换 | 默认静默，不做结算 |
| 白昼 | 农业 / 平稳采集 | 晴天田垄、雨润根茎、雨钓耐心、雨天河岸月露 |
| 黄昏 | 野外采集 / 返程 | 黄昏采集者、林地小径、暗沼边缘开始活跃 |
| 夜晚 | 风险探索 | 满月僵尸群、雷雨骷髅巡游、暗处采集、满月夜钓 |
| 午夜 | 高辨识度夜间事件 | 新月夜蛛、满月僵尸群、雷雨骷髅巡游静默增强 |

默认只有白昼和夜晚会发聊天 + ActionBar。黎明、黄昏、午夜默认静默，避免一个夜晚刷太多消息。

## 默认天气联动

| 天气 | 默认影响 |
| --- | --- |
| CLEAR 晴天 | 白昼农田成长略好，日常探索稳定 |
| RAIN 雨天 | 根茎作物、蘑菇、甘蔗、河边、海边、钓鱼略有收益 |
| THUNDER 雷暴 | 夜晚风险更高，雷雨骷髅和雷骨碎片出现，铜矿脉有小概率产出雷骨碎片 |

天气不会只影响怪物。默认配置会把雨天拉回原版生存日常：钓鱼、河岸、沼泽、蘑菇、甘蔗和海边路线都有轻价值。

## 三种生态材料

Moonlife 默认只保留三种轻用途材料，避免把材料线做成复杂养成资源。

### 影尘

定位：新月、夜行、暗处采集材料。

默认来源：

- 新月夜蛛。
- 新月 / 残月在黑森林、沼泽、红树林沼泽采集蘑菇、蛛网、藤蔓。

默认用途：

- 右键 `SOUL_LANTERN` 形态的影尘灯盏，消耗 1 个影尘，获得短时夜视。
- 可作为服主自定义夜视食物、潜行辅助、暗色装饰粉、夜间祭坛材料。

平衡思路：只服务夜间便利，不做顶级装备材料。

### 月露种子

定位：农业、生长、收获、钓鱼材料。

默认来源：

- 满月 / 盈凸月成熟作物。
- 雨天河岸采集甘蔗、蘑菇、海带和海草。
- 雨天钓鱼。
- 满月夜钓鱼。

默认用途：

- 右键 `COMPOSTER` 形态的月露堆肥台，消耗 2 个，获得短时恢复和少量经验。
- 可作为服主自定义特殊种子、肥料、轻食物、农业祭坛材料。

平衡思路：让农田和雨钓有一点惊喜，不做自动化刷资源核心。

### 雷骨碎片

定位：雷暴、风险探索、雷系小事件材料。

默认来源：

- 雷雨夜击败骷髅或流浪者。
- 雷暴天气下挖掘铜矿石或深层铜矿石。

默认用途：

- 右键 `LIGHTNING_ROD` 形态的雷骨避雷针，消耗 1 个，雷雨夜获得短时抗性和速度。
- 可作为服主自定义一次性消耗品、雷暴夜辅助物、装饰与小型祭坛材料。

平衡思路：鼓励雷雨夜冒险，但不做重数值装备进阶。

## MythicMobs 定位

默认方案不依赖 MythicMobs。

Moonlife 保留 MythicMobs 软接入能力，但它不是默认玩法中心：

- 默认 `monsters.yml` 使用原版 `ZOMBIE`、`SPIDER`、`SKELETON`。
- 如果安装 MythicMobs，可以把单条规则的 `spawn-backend` 改成 `MYTHIC_MOB`。
- Moonlife 负责生态条件、限速、冷却、权重、月相、日相、天气、群系判断。
- MythicMobs 负责自定义怪物本体、技能、显示名和行为。

未安装 MythicMobs 时，插件仍可正常启动。

## 配置结构

默认配置结构保持扁平，服主不需要翻很多旧文件。

| 路径 | 作用 |
| --- | --- |
| `config.yml` | 运行时开关、月相/日相通用设置、性能保护、荒野判断、刷新节流 |
| `monsters.yml` | 原版怪物或 MythicMobs 刷新规则 |
| `crops.yml` | 作物成长、额外收获、骨粉/自动化影响 |
| `buffs.yml` | 玩家状态、经验倍率、掉落倍率、移动、承伤、药水 |
| `altars.yml` | 生态材料、材料掉落、热点、小型祭坛、可选悬赏/图鉴扩展 |
| `moon-phases/*.yml` | 每个月相启用哪些怪物、作物、Buff、热点、材料掉落 |
| `solar-phases/*.yml` | 每个日相的时间区间、启用规则和公告配置 |
| `lang/zh_cn.yml` | 中文语言文件 |

相位文件只负责“这个相位启用哪些功能”。具体规则数值仍在 `monsters.yml`、`crops.yml`、`buffs.yml`、`altars.yml` 中维护。

## 配置示例

给某个月相增加一个材料掉落规则：

```yaml
functions:
  material-drops:
    - rain_fishing_moonlit_seed
```

新增一个保守的采集材料规则：

```yaml
material-drops:
  forest_leaf_trace:
    display-name: "林叶月露"
    objective: BREAK_BLOCK
    targets: [OAK_LEAVES, BIRCH_LEAVES]
    worlds: [world]
    biomes: [FOREST, BIRCH_FOREST]
    moon-phases: [WAXING_CRESCENT]
    solar-phases: [DAY, DUSK]
    weather: [RAIN]
    wilderness-only: true
    chance: 0.015
    amount: [1, 1]
    reward-materials: [moonlit_seed]
    cooldown: 160
    priority: 10
```

## 命令

| 命令 | 说明 | 权限 |
| --- | --- | --- |
| `/lunarphase` | 查看当前世界月相 | `ecology.info` |
| `/solarphase` | 查看当前世界日相 | `ecology.info` |
| `/ecology info` | 查看当前世界、月相、日相、天气 | `ecology.info` |
| `/ecology preview` | 预览当前位置命中的刷怪规则 | `ecology.preview` |
| `/ecology inspect` | 解释当前位置命中的刷怪、作物、状态、热点、材料掉落 | `ecology.debug` |
| `/ecology validate` | 校验配置和软依赖状态 | `ecology.debug` |
| `/ecology reload` | 热重载配置 | `ecology.reload` |
| `/ecology setmoon <phase> [world]` | 手动设置月相 | `ecology.setphase` |
| `/ecology setsolar <phase> [world]` | 手动设置日相 | `ecology.setphase` |
| `/ecology materials` | 查看生态材料来源和用途 | `ecology.info` |
| `/ecology bossbar` | 开关生态危险调试 BossBar | `ecology.debug` |

## PlaceholderAPI

变量前缀为 `%moonlife_<变量>%`。

| 变量 | 输出 |
| --- | --- |
| `%moonlife_moon%` | 当前月相中文名 |
| `%moonlife_solar%` | 当前日相中文名 |
| `%moonlife_weather%` | 当前天气中文名 |
| `%moonlife_phase_summary%` | 月相 / 日相 / 天气摘要 |
| `%moonlife_spawn_rules%` | 当前命中的刷怪规则 |
| `%moonlife_crop_rules%` | 当前命中的作物规则 |
| `%moonlife_buff_rules%` | 当前命中的状态规则 |
| `%moonlife_material_drops%` | 当前命中的材料掉落规则 |
| `%moonlife_danger%` | 当前危险等级 |
| `%moonlife_hotspot%` | 当前热点 |
| `%moonlife_materials%` | 生态材料来源和用途 |

## 构建

```powershell
.\gradlew.bat build --no-daemon
```

目标环境：

- JDK 25
- Paper API `26.1.2.build.+`
- 插件 `api-version` 当前保持 `26.1.1`，用于兼容当前测试服 `paper-26.1.1`。如果服务端升级到 `26.1.2`，可以同步调整 `plugin.yml`。

## 升级说明

这次默认内容层做了方向纠偏：

- 移除了默认黎明结算玩法。
- 默认材料从多条收集名词收敛为三种轻用途材料：影尘、月露种子、雷骨碎片。
- 默认悬赏和成就不再作为主线推进，保留为服主扩展位。
- 新增 `altars.yml -> material-drops`，用于配置击杀、钓鱼、采集、成熟收获的轻量材料来源。
- `moon-phases/*.yml` 和 `solar-phases/*.yml` 支持 `functions.material-drops`。

旧文件兼容：

- `spawn-rules.yml` 仍可作为 `monsters.yml` 的旧名兜底。
- `crop-rules.yml` 仍可作为 `crops.yml` 的旧名兜底。
- `buff-rules.yml` 仍可作为 `buffs.yml` 的旧名兜底。
- `features.yml` 仍可作为 `altars.yml` 的旧名兜底。
- `moon-phases.yml`、`solar-phases.yml`、`settings/config.yml` 仍保留读取兜底。

建议升级方式：

1. 备份旧 `plugins/Moonlife` 目录。
2. 删除旧默认配置中不再需要的 `dawn_dew`、`pale_fiber`、`verdant_grain`、`cycle_badge` 等材料配置。
3. 使用新的 `altars.yml`、`crops.yml`、`buffs.yml`、`moon-phases`、`solar-phases` 作为基线。
4. 只把自己真正需要的旧规则迁移回新文件。
5. 执行 `/ecology validate`，确认没有缺失材料或软依赖警告。

## 二次配置建议

- 想强化农业：优先调 `crops.yml` 的成长倍率和 `material-drops.moonlit_harvest_seed` 的概率，不要直接给高倍率。
- 想强化钓鱼：调 `rain_fishing_moonlit_seed` 和 `fullmoon_fishing_moonlit_seed`，建议概率不超过 `0.08`。
- 想强化挖矿：优先增加洞穴热点和低概率矿石材料掉落，不建议直接复制矿物掉落。
- 想强化野外跑图：增加热点和采集材料规则，不要把它做成每日任务链。
- 想接 MythicMobs：只替换单条怪物规则的后端，不要让默认玩法完全依赖自定义怪。

Moonlife 的最佳状态是：玩家不需要专门学习一套新 RPG 系统，但会感觉今天的世界和昨天不太一样。
