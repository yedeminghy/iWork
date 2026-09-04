# 场景示例

## 目录

- 单指标趋势
- 多指标查询
- 对象歧义
- 对象集合筛选
- 对象筛选语义歧义
- 指标歧义或缺参
- 部分成功
- 完整指标方案
- 方案输出投影

## 单指标趋势

用户：`组合 I32Q0192 在 2025 年 1 月的累计收益走势。`

执行：

1. 解析对象代码 `I32Q0192`、类型 `0`、日期 `2025-01-01` 至 `2025-01-31`。
2. 调用 `select_indicators` 选择累计收益率指标。
3. 调用 `describe_indicators` 校验单位和参数。
4. 调用 `calculate_indicators`。
5. 生成折线图，同时返回展示值、原始值和 MCP 证据。

## 多指标查询

用户：`比较组合 I32Q0192 今年以来的累计收益率、最大回撤和波动率。`

执行：

- 将三项指标分别传给 `select_indicators`。
- 为每个指标独立保存参数。
- 单位一致时可生成多系列图；单位或尺度明显不同时生成 `charts[]`。
- 任一指标无数据时仍展示成功指标，并将顶层状态设为 `PARTIAL_SUCCESS`。

## 对象歧义

用户：`查询安心一号最近三个月收益走势。`

若 `search_objects` 返回多个“安心一号”候选，不计算指标。返回：

```json
{
  "status": "WAITING_INPUT",
  "summary": "匹配到多个计算对象，请先确认。",
  "objectCandidates": [
    {"code": "PORT-001", "name": "安心一号组合", "type": 0},
    {"code": "PLAN-001", "name": "安心一号计划", "type": 1}
  ],
  "missingFields": ["resolvedObject"]
}
```

## 对象集合筛选

用户：`查询重点监控的固收类组合。`

执行：

1. 判定对象类型为 `0=组合`，请求意图为对象集合筛选。
2. 去除“查询”“组合”等意图词和类型词，将正向交集拆为两个 `semanticClauses`，保留完整业务短语。
3. 调用 `filter_objects`。没有受控代码前，不自行构造 `invStyle`、标签代码或枚举值。
4. 若返回多项且用户只要求名单，展示当前页并保留 `nextCursor`；若同时要求指标计算，则在 10 页/100 对象上限内续页收集，以 `objectSet` 一次提交。

以下拆分是不允许的：

- `semanticTerms=["重点","监控","固收","类"]`：破坏业务短语。
- `semanticTerms=["重点监控或固收类"]`：不是原子条件。
- 把“近三个月收益率”放入 `semanticTerms`：它属于计算日期和指标，不属于对象过滤。

## 对象筛选语义歧义

用户：`查询稳健组合的收益率。`

若 `filter_objects` 返回：

```json
{
  "status": "WAITING_INPUT",
  "code": "AMBIGUOUS_FILTER",
  "ambiguousTerms": [
    {
      "term": "稳健",
      "candidates": [
        {"kind": "ATTRIBUTE_VALUE", "attributeCode": "invStyle", "value": "STABLE_EQUITY"},
        {"kind": "ATTRIBUTE_VALUE", "attributeCode": "productClass2", "value": "STABLE"}
      ]
    }
  ]
}
```

展示候选含义并等待用户确认。不得默认选择第一项，也不得改用无条件对象查询。

对于并集或否定条件，模型保留逻辑，服务端解析受控代码：

- `固收类或权益类组合`：两个原子使用相同 `logicalGroup` 和 `logicalOperator=OR`；服务端只在同属性值或标签任一匹配可安全编译时继续。
- `非重点监控组合`：原子使用 `polarity=NEGATIVE`；服务端编译为 `tagFilters.none` 或属性 `NE`。
- `规模超过1亿且成立日期不晚于2024年末`：分别生成数值 `GT` 和日期 `LTE` 原子，保留 `1亿`、`2024年` 原文值供服务端类型归一化。

## 指标歧义或缺参

用户：`查询 I32Q0192 的超额收益。`

- 若 `select_indicators` 返回多个置信度接近的候选，展示候选指标代码、名称和置信度，请用户选择。
- 若所选指标要求 `indexCode`、`riskFreeCode`、`calCode`、`bmCode`、`confidence` 等参数：
  1. 先通过 `describe_indicators` 确认参数定义；
  2. 用户和合法语义均未提供时，按参数代码精确查询 [默认信息契约](default-information.md)，命中后直接使用并通过 `parameterSchema` 校验；
  3. 默认信息契约没有该参数时，再调用 `get_indicator_parameter_options` 获取动态选项；
  4. Schema/服务端默认值仍不能补齐必填参数时返回 `WAITING_INPUT`。
- 默认信息契约中的 `bmCode/indexCode` 仅用于同名参数缺失的情形；用户明确要求特定基准时不得用契约默认值覆盖。指标或参数仍有歧义时，不要调用 `calculate_indicators`。

## 部分成功

用户：`展示 I32Q0192 的累计收益率和不存在的测试指标。`

- 对累计收益率正常计算并生成图表。
- 对测试指标保留 `NO_CANDIDATE` 或服务返回错误。
- 返回 `status=PARTIAL_SUCCESS`，不得让成功图表掩盖失败指标。

## 完整指标方案

用户：`查询组合 I32Q0192 在 2025 年 1 月的完整 Brinson 大类资产归因。`

执行：

1. 将 `calculationTarget` 设为 `INDICATOR_SCHEME`，解析对象和日期。
2. 调用 `search_indicator_schemes(query="Brinson大类资产归因", objectType=0)`。
3. 调用 `describe_indicator_schemes(schemeCodes=["BrinsonAssetAttribution"])`。
4. 调用 `calculate_indicator_scheme`，不传 `_idxCodeList`、`relativeLayer`、`attributionType` 或未提供的 `scenarioId`；MCP 自动发送 `appKey`、`moduleNo` 和 `portCalcType=single`。
5. 使用根节点生成摘要，使用一级资产类别生成分组柱状图，并保留完整层级行集。

## 方案输出投影

用户：`按 BrinsonAssetAttribution 计算，只展示资产配置和个券选择。`

- 仍执行完整方案。
- `requestedOutputCodes` 只传经 `describe_indicator_schemes.outputIndicators` 验证的两个代码。
- 不把该请求拆成两个单指标计算，也不自行验证 Brinson 恒等式。
