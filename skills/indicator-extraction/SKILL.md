---
name: indicator-extraction
description: "从自然语言请求中提取一个或多个业务指标或指标方案，按代码或名称解析单一计算对象，或按属性、标签、关键词和语义条件筛选对象集合，再调用获准的对象、指标与指标方案 MCP Tool 查询指标口径或完成计算，并按用户意图返回 JSON、中文报告或可直接打开的 ECharts/HTML 图表。用于组合、计划、投管人机构或投资经理的指标定义、公式、口径、数据来源、数值、走势、对比、排名、Brinson/Campisi 归因、完整方案分析及可视化查询。"
---

# 指标提取 Skill

## 目标

把自然语言指标问题转换为可审计的口径检索、单指标计算或指标方案计算请求，并根据用户意图选择机器可读 JSON、中文报告或可直接打开的 HTML 图表。始终以 MCP 返回结果为事实来源，不自行计算指标或归因公式，不生成或执行 SQL。

## 执行前准备

1. 读取 [MCP Tool 契约](references/mcp-tool-contracts.md)，确认可用工具、参数名和状态字段。
2. 读取 [默认信息契约](references/default-information.md)，确认 `_mergeDimE` 等 Tool 顶层参数的显式覆盖规则，以及 `indexCode`、`riskFreeCode`、`calCode`、`bmCode`、`confidence` 等常用指标参数默认值。
3. 需要组装图表或最终结果时，读取 [输出与 ECharts 契约](references/output-echarts-contract.md)。
4. 需要判断歧义、部分成功或多指标展示方式时，参考 [场景示例](references/examples.md)。
5. 只调用 `agents/openai.yaml` 中声明的 MCP Server；不要绕过 MCP Gateway 或调用白名单外能力。

## 工作流

### 1. 判定请求类型

- 对指标数值、走势、对比、排名、分布或图表请求执行本流程。
- 对只要求查询或列出组合、计划、投管人机构、投资经理等对象而不要求指标的请求，只执行步骤 2 和步骤 3，返回对象与分页证据；不要继续调用指标选择、描述或计算 Tool。
- 对只询问定义、算法、公式、口径或数据来源的请求，调用 `xquant-indicator-mcp/search_indicators` 检索指标信息；不解析计算对象，不调用 `calculate_indicators`。多个候选无法唯一确定时展示候选并等待确认。
- 同时包含口径说明和数据查询时，先完成数据查询，再将 MCP 返回的指标描述作为口径证据；不要编造缺失定义。

### 2. 提取语义查询

从用户问题和显式参数中提取：

- 计算对象代码或名称；
- 对象类型：`0=组合`、`1=计划`、`2=投管人机构`、`3=投资经理`；
- 对象解析意图：按代码/名称解析单一对象，或按属性、标签、关键词筛选对象集合；
- 日期范围，统一为闭区间 `yyyy-MM-dd`；
- 一个或多个指标名称、别名或代码；
- 计算目标 `calculationTarget=AUTO|INDICATORS|INDICATOR_SCHEME`；
- 指标方案代码、名称或别名、方案参数，以及希望展示的输出项；
- 对含时间修饰语的指标，提取“完整指标候选”和可回退的“时间 + 基础指标”；
- 频率、基准、日历、资产类别等逐指标动态参数；
- 图表意图，如走势、对比、排名或构成。
- 输出模式 `outputMode`：`json`、`report` 或 `html`。

显式参数优先于自然语言推断。可依据宿主当前日期换算“本月”“今年以来”“去年”“近三个月”“近半年”“近一年”和“近 N 天”，但必须在输出中保留换算后的绝对日期。无法可靠确定必填信息时返回 `WAITING_INPUT`，不要猜测。

按以下规则确定计算目标：

- 明确方案代码、“按××方案/模型分析”、精确方案名称/别名、“整套/完整 Brinson 或 Campisi 归因”时使用 `INDICATOR_SCHEME`。
- 明确单指标代码或“只算某个指标”且未提方案时使用 `INDICATORS`。
- 同时命中方案和单指标且语义不清时返回 `WAITING_INPUT`。
- “按方案计算但只展示若干项”仍完整执行方案，只通过 `requestedOutputCodes` 投影响应。

### 时间修饰指标：完整指标优先

当指标短语同时包含时间语义和度量语义时，先将整个短语视为一个指标名称或别名检索；时间语义不得在首轮检索时被自动剥离。例如：

- `本季度以来组合收益率`
- `今年以来累计收益率`
- `本月以来组合收益率`
- `近6月组合收益率`
- `近3年组合年化波动率`
- `成立以来组合标准差`

同时保留回退拆分：时间短语用于构造 `dateRange`，基础指标用于第二轮检索。若问题包含“截至某日/某月末”等锚点，时间区间必须以该锚点结束，而非宿主当前日期。

“成立以来”回退时必须从可信对象数据取得成立日；无法取得时返回 `WAITING_INPUT`，不得以任意历史日期代替。

按以下规则确定输出模式：

- 用户明确要求“返回 JSON”“结构化结果”或“接口返回”时使用 `json`。
- 用户要求“直接展示”“不要 JSON”“给我看图”或“ECharts 图表”时使用 `report`。
- 用户要求“生成页面”“生成 HTML”或“可打开的图表”时使用 `html`。
- 未明确要求机器消费时默认使用 `report`。
- 无论输出模式如何，内部都保留结构化结果、标准化行数据、ECharts `option` 和执行证据。

集合筛选采用“两段式语义编译”：模型只拆自然语言原子，MCP 再解析受控代码并校验类型。不要让模型直接生成内部属性代码、标签代码或对象代码。

- 优先生成 `semanticClauses[]`。每项包含稳定 `id`、原始 `term`，以及可选的 `fieldTerm`、`operatorHint`、`valueTexts`、`polarity`、`logicalGroup`、`logicalOperator`。
- `AND` 条件拆成独立原子；同一 `logicalGroup` 的 `OR` 原子表示并集；否定使用 `polarity=NEGATIVE`，不要把否定词丢掉。
- 比较条件保留字段和值文本，例如“规模超过1亿”生成 `fieldTerm="资产规模"`、`operatorHint="GT"`、`valueTexts=["1亿"]`。数值单位和中文日期由 MCP 归一化。
- 不拆坏固定业务短语，例如“固定收益”“投资经理”“重点监控”各自保持完整；对象类型词、指标词、日期范围和“查询、展示、排名”等意图词不进入对象语义原子。
- 对象代码或单一对象名称进入直接解析或 `search_objects.query`；目标对象集合自身的名称关键词进入 `filter_objects.keyword`，不要当作属性条件。若一个对象名称用于限定另一类型的目标对象，例如“富国基金下的投资经理”，则按步骤 3 的关联对象规则先解析限定对象代码，不要把该名称放入目标对象的 `keyword` 或直接作为属性值。
- 显式 `attributeFilters`、`tagFilters` 和用户确认过的受控候选优先于模型拆分；不得根据模型知识发明属性代码、标签代码或枚举值。
- 仅有简单正向交集且客户端不支持 `semanticClauses` 时，才回退为 `semanticTerms`；`semanticTerms` 不表达 OR 或否定。
- 一个短语同时含对象条件和指标条件时分别提取。例如“重点监控的固收组合近三个月收益率”生成两个对象语义原子、对象类型 `0`、指标“收益率”和独立日期范围。
- 计算前可先以相同条件调用 `filter_objects(resolveOnly=true)` 预解析。歧义必须确认；成功后使用返回的 `resolvedClauses` 与 `appliedFilters` 作为审计证据。

### 3. 解析计算对象

- 已提供明确对象代码和对象类型时直接使用。
- 按代码或名称解析单一对象时，调用 `xquant-indicator-mcp/search_objects`，传入对象描述和已知对象类型。
- `requiresConfirmation=true` 时必须等待用户确认；否则仅有一个候选或代码/名称精确匹配一个候选时选中该对象。
- 当目标对象集合通过另一类业务对象的名称或代码限定时，先解析关联对象，再筛选目标对象；不要把关联对象名称直接作为普通字符串属性值传给 `filter_objects`。关联对象代码只能来自用户显式提供、用户确认过的候选或本轮 `search_objects` 返回结果，不得根据模型知识推断。
  - 只使用以下已确认关系映射：`计划(objectType=1) → 组合(objectType=0)` 使用 `planCode`；`投管人机构(objectType=2) → 组合(objectType=0)` 使用 `invManager`；`投管人机构(objectType=2) → 投资经理(objectType=3)` 使用 `currentOrganization`。不得自行扩展关系或属性代码。
  - 对受支持的关系，先调用 `search_objects` 解析限定对象；仅在返回唯一对象或唯一精确匹配时读取其 `objectCode`，再调用 `filter_objects` 查询目标对象集合。
  - “某计划下的组合”传入 `objectType=0` 和 `attributeFilters=[{"code":"planCode","operator":"EQ","values":["<计划 objectCode>"]}]`；“某投管人下的组合”传入 `objectType=0` 和 `attributeFilters=[{"code":"invManager","operator":"EQ","values":["<投管人机构 objectCode>"]}]`；“某投管人下的投资经理”传入 `objectType=3` 和 `attributeFilters=[{"code":"currentOrganization","operator":"EQ","values":["<投管人机构 objectCode>"]}]`。
  - 对“某投资经理下的组合”，当前没有获准的经理—组合关系映射；返回 `WAITING_INPUT` 或受控的 `RELATION_UNAVAILABLE` 限制说明。不得改查该经理所在机构的全部组合，因为两者语义不同。
  - 例如“富国基金”必须先解析出 Tool 返回的机构代码，再用于筛选；不得直接发送 `currentOrganization EQ "富国基金"` 或 `invManager EQ "富国基金"`。
  - 关联对象返回 `requiresConfirmation=true`、`resolution=AMBIGUOUS` 或存在多个无法唯一确定的候选时，返回 `WAITING_INPUT` 并展示机构候选；不要继续调用 `filter_objects`。
  - 在执行证据中分别保留关联对象解析结果和目标对象筛选结果，包括关联对象类型、名称、代码、请求 ID，以及筛选返回的 `appliedFilters`、`snapshotVersion` 和 `dataAsOf`。
- 用户要求“哪些、所有、某类、带某标签、满足某条件”等对象集合，或对象由属性、标签、关键词条件描述时，调用 `xquant-indicator-mcp/filter_objects`。传入必填 `objectType`，并按需传入 `semanticClauses`、`keyword`、受控 `attributeFilters` 或受控 `tagFilters`。
- `filter_objects.status=WAITING_INPUT` 时展示 `ambiguousTerms[].candidates` 并等待用户确认；不要按置信度最高项擅自选择。
- `filter_objects.status=SUCCESS` 且 `completeness=EXACT` 时读取 `items` 和 `page`。一项结果使用单对象计算；多项结果使用同类型 `objectSet`。
- 用户只要求对象名单且未明确要求“所有/全部”时，默认返回第一页及 `nextCursor`。明确要求“所有/全部”时自动续页，最多 10 页或 1000 个对象；达到任一上限且仍有后续页时，返回已获取数量、`nextCursor` 和未穷尽告警，不得声称结果完整。
- 用户要求指标计算时，可自动续页收集完整集合，但最多 10 页、100 个对象；达到任一上限且仍有后续页时返回 `WAITING_INPUT`，要求缩小条件，不能把部分集合当作完整集合计算。
- 所有续页必须原样保留筛选条件，只替换 `cursor`。结果按对象代码去重；不同对象类型不得合并进同一 `objectSet`。
- 多个候选无法唯一确定时返回候选列表并等待用户确认；不要按排序位置擅自选择。
- 没有候选时说明未找到满足条件的对象并停止计算。

### 4. 选择并校验计算目标

#### 4.1 单指标

- 一般路径调用 `xquant-indicator-mcp/select_indicators`，让服务为每项指标诉求选择一个可调用指标，并生成 `selectedIndicatorRequests`。
- **默认组合口径（硬性筛选规则）**：当已解析计算对象的类型为组合（0）、计划（1）、投管人机构（2）或投资经理（3），且用户没有在**指标诉求**中明确要求基准、养老金、股票、债券或基金口径时，将无口径限定的指标诉求解释为“组合”口径。对象名称、管理人名称或产品名称中偶然出现“基金”“债券”等词，不构成对相应指标口径的明确要求。
  1. 指标检索和候选比较使用组合语义；对泛称“收益率”，默认检索/选择 `组合累计收益率`（时间词仅用于确定 `dateRange`）。计算对象本身及其 `objectType` 不因此改写。
  2. 将组合口径显式写入 `metricQueries`，例如把“上月收益率”在时间拆分后的第二轮查询写为 `组合累计收益率`，而不是仅传 `收益率`。
  3. 仅保留名称、别名、指标定义或对象口径明确为组合的候选；排除基准、养老金、股票、债券、基金及其他非组合口径候选，即使其相似度或排序更高。若没有合格的组合候选，返回 `WAITING_INPUT` 或受控失败，不得以非组合候选替代。
  4. 用户明确写出上述任一口径（例如“基准累计收益率”“养老金收益率”“股票收益率”“债券收益率”或“基金收益率”）时，按其明确口径选择；此时不适用默认组合口径。
- 示例：`重庆电力_海富通 上月收益率` 在对象解析为上述任一类型且问题未明确其他口径时，日期范围取上月，第二轮 `metricQueries` 使用 `组合累计收益率`；不得选择 `基准累计收益率`、`养老金收益率` 等候选。
- 对“时间修饰指标”按以下顺序选择：
  1. 首轮将完整短语原样放入 `metricQueries`，例如 `今年以来累计收益率`；
  2. 只在返回的指标代码、名称或知识库别名能明确表达该完整时间语义且对象语义一致时采用。仅因相似度较高、或命中“基准/指数/年化”等不同对象或口径的指标，不得视为命中；
  3. 首轮无候选、低置信度、歧义、缺少完整语义，或候选口径不一致时，再将其拆为时间和基础指标。时间只用于规范化 `dateRange`，第二轮 `metricQueries` 仅传基础指标，例如 `累计收益率`、`组合收益率`、`组合年化波动率`、`组合标准差`；
  4. 在最终输出的 `semanticQuery`、`calculation` 和证据中记录选择模式：`full_metric` 或 `time_metric_fallback`，以及回退后的日期范围。
- 回退不等于放宽语义校验：基础指标仍须匹配用户的对象、收益/风险口径和年化要求；不匹配时返回 `WAITING_INPUT` 或受控失败，不得改用基准、指数或其他相近指标。
- 用户给出已知指标代码时仍调用 `describe_indicators` 校验代码、单位和参数定义。
- `select_indicators` 返回 `AMBIGUOUS`、`LOW_CONFIDENCE`、`NO_CANDIDATE` 或必填参数缺失时，不得静默替换指标。
- 调用 `xquant-indicator-mcp/describe_indicators` 获取指标口径、单位、值类型和 `parameterSchema`；指标定义参数与对象类型无关。
- 以下参数补全规则仅适用于每个指标自己的 `indicators[].parameters`，不适用于 `calculate_indicators` 的 Tool 顶层请求参数。
- 指标参数补全按以下优先级执行：**用户显式参数 > 合法语义推断 > [默认信息契约](references/default-information.md)中的指标默认值 > 指标计算接口默认值 > 公共默认值 > `get_indicator_parameter_options` 动态选项 > Schema 默认值 > 服务端默认值**。
- 对每个缺失参数依次处理：
  1. 用户或合法语义已经提供值时，直接进入 Schema 校验；
  2. 未提供时，按参数代码精确查询默认信息契约：先匹配当前指标代码的指标默认值，再匹配当前指标计算接口的接口默认值，最后匹配公共默认值；命中后使用优先级最高的适用值，不调用动态选项 Tool；
  3. 三类默认值均未配置该参数，且参数定义声明动态候选源时，调用 `get_indicator_parameter_options`；
  4. 仍无法获得合法值时，再使用 Schema/服务端默认值；必填参数仍缺失则返回 `WAITING_INPUT`。
- 契约默认值不得覆盖用户值、合法语义值、固定参数或隐藏参数；使用前仍须通过对应指标的 `parameterSchema`，并在执行证据中记录参数值及来源范围：`DEFAULT_INFORMATION_INDICATOR`、`DEFAULT_INFORMATION_INTERFACE` 或 `DEFAULT_INFORMATION_PUBLIC`。

#### 4.2 指标方案

- 调用 `xquant-indicator-mcp/search_indicator_schemes` 检索方案；只有 `resolution=EXACT|UNIQUE` 且 `requiresConfirmation=false` 时继续。
- 调用 `xquant-indicator-mcp/describe_indicator_schemes` 获取适用对象、最多对象数、有效参数 Schema、自动参数、输出维度、输出指标和定义版本。
- 参数优先级为：调用方显式值 > 合法语义值 > 方案默认值 > 关联指标/接口默认值 > 公共默认值 > 动态选项 > Schema/平台默认值。`PARAM_VALUE_TYPE` 为空的方案参数明细视为可覆盖默认值，不视为固定值。
- 不要求也不发送平台自动参数 `relativeLayer`、`attributionType`。`scenarioId` 仅在调用方提供时发送，不同时发送 `scenarioIds`；缺省时交由平台处理。
- 禁止传入或覆盖 `moduleNo`、`appKey`、`_idxCodeList`、`portCode`、`portType`、`portCalcType` 和日期映射等保留参数。
- 方案参数选项 Tool 为预留能力；不可用且缺少无默认值的可见必填参数时返回 `WAITING_INPUT`。

### 5. 执行计算

#### 5.1 单指标

调用 `xquant-indicator-mcp/calculate_indicators`，传入：

- 单对象传 `object={code,type}`；同类型多对象传 `objectSet={codes,type}`。两者必须且只能提供一个；
- 规范化的 `dateRange.begin` 与 `dateRange.end`；
- Tool 顶层参数 `_mergeDimE`：仅当用户显式指定全局覆盖值时传入。用户未指定时必须省略，由 MCP Server 根据每个指标的 `interfaceCode` 读取接口配置并自动拆分上游批次，未命中接口配置时使用服务端全局默认值。该参数不得放入 `indicators[].parameters`，Skill 不自行推断接口映射，也不因多个接口而拆分 `calculate_indicators` 调用；
- 每个指标独立的 `code` 与 `parameters`。

保留每个指标自己的参数作用域，不要把多个指标的同名参数合并为全局参数。不要根据模型知识、公式或其他数据源补算缺失值。

从响应 `meta.mergeDimensions[]` 读取并记录每个指标的 `indicatorCode`、`interfaceCode`、最终 `value` 和 `source`。来源可能为 `REQUEST_OVERRIDE`、`INTERFACE_CONFIGURATION` 或 `GLOBAL_DEFAULT`；不得使用请求缺省值替代服务端返回的最终解析证据。

按返回状态处理：

- `SUCCESS`：生成完整结论和图表；
- `PARTIAL_SUCCESS`：展示成功指标，同时逐项列出失败或无数据原因；
- `FAILED`：不生成看似成功的图表，返回受控失败信息与可追溯错误；
- `NO_DATA` 或空点集：明确说明无可视化数据，不以零值替代。

#### 5.2 指标方案

调用 `xquant-indicator-mcp/calculate_indicator_scheme`，传入单对象或同类型 `objectSet`、闭区间 `dateRange`，以及唯一 `scheme={code,parameters,requestedOutputCodes}`。

- 单次最多 50 个对象；超过时返回 `WAITING_INPUT`，不要自动拆成多次方案计算。
- 多对象由 MCP 向平台重复发送同名 `portCode`；单、多对象都使用 `portCalcType=single`。
- MCP 始终发送与 `calculate_indicators` 相同来源的服务端 `appKey`，使用方案代码作为 `moduleNo`，且不发送 `_idxCodeList`。
- LIST 参数按同名查询参数重复序列化。
- 读取 `rows` 作为层级行集，读取 `results` 作为逐指标投影；不要自行补齐缺失字段或以零代替。
- 保留 `moduleRequestId`、`definitionVersion`、`actualRowCount`、`upstreamDeclaredCount` 和 `parameterSources`。
- 上游 `count=0` 不能单独用于判断无数据；以 `rows` 和 `actualRowCount` 为准。

### 6. 生成结论和 ECharts 图表

- 优先使用 `displayValue` 展示和绘图，同时保留 `rawValue` 作为证据。
- 将 `results[].points[]` 或 `results[].values[]` 统一为行数据，保留指标代码、指标名称、单位、日期和维度。
- 趋势使用折线图；分类对比或排名使用柱状图；仅对非负且具有构成含义的数据使用饼图。
- 多指标单位不一致时拆分图表，或使用清晰标注的多 Y 轴；不得把不同量纲混在同一无标注坐标轴。
- ECharts `option` 必须是纯 JSON 兼容对象，不嵌入函数、任意 JavaScript 或远程脚本。
- Brinson 根节点优先展示超额收益、资产配置、个券选择和交叉项；一级资产类别用分组柱状图，层级明细用表格或树表。
- 存在负权重或负贡献时禁止饼图；同名节点使用 `nodeId` 或完整路径区分，不按 `nodeName` 合并。
- 按 `outputMode` 输出：`json` 返回完整结构化对象；`report` 返回中文结论、关键数据、ECharts `option` 和压缩证据；`html` 生成包含图表、数据表和证据的本地 HTML 文件并返回可打开路径。

## 输出要求

先按 [输出与 ECharts 契约](references/output-echarts-contract.md) 生成内部结果，再根据 `outputMode` 输出：

- `json`：返回完整 JSON 兼容对象，计算请求至少包含 `status`、`summary`、`resolvedObject` 或 `resolvedObjects`、`semanticQuery`、`calculation`、`chart` 或 `charts`、`evidence`、`warnings`、`errors` 和 `missingFields`。纯口径请求返回指标候选或定义、检索证据和歧义信息，不伪造 `calculation` 或图表。
- `report`：不返回完整顶层 JSON；按“结论、关键数据、ECharts option、执行证据、限制说明”展示。纯口径请求按“指标定义、公式/口径、数据来源、候选与证据”展示。
- `html`：生成可直接打开的本地 HTML 文件，内嵌摘要、ECharts 图表、标准化数据表、执行证据和限制说明；纯口径请求没有可视化数据时生成口径说明页，不虚构图表数据。

结论必须能由返回数据直接支持。标注数据日期范围、指标单位、部分成功状态和限制，不夸大趋势，不给出未经请求的投资建议。

## 安全与审计

- 继承 MCP Gateway 提供的租户、用户、请求和权限上下文，不伪造可信请求头。
- 不输出凭证、内部连接信息、原始 SQL 或超出用户权限的对象数据。
- 不执行指标元数据中携带的 SQL；动态选项只能通过受控 MCP Tool 获取。
- 保留 Server、Tool、指标/方案定义版本、请求 ID、方案上游请求 ID、数据截止日期和错误信息等可用证据。
- 对写操作、权限失败和越权对象查询采用受控失败，不降级为无约束访问。
