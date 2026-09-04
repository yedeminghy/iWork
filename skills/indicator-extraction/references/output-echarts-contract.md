# 输出与 ECharts 契约

## 目录

- 顶层输出
- 输出模式
- 数据归一化
- 图表选择
- ECharts option
- 结论与证据

## 顶层输出

返回 JSON 兼容对象：

```json
{
  "status": "SUCCESS",
  "summary": "组合 I32Q0192 在 2025 年 1 月累计收益率总体上升，期末展示值为 1.2%。",
  "resolvedObject": {
    "code": "I32Q0192",
    "name": "示例组合",
    "type": 0
  },
  "semanticQuery": {
    "metricCodes": ["gbPDefaultRtnCumulative"],
    "metricNames": {"gbPDefaultRtnCumulative": "组合累计收益率"},
    "dateRange": {"begin": "2025-01-01", "end": "2025-01-31"},
    "chartIntent": "trend"
  },
  "calculation": {
    "status": "SUCCESS",
    "results": [],
    "errors": [],
    "meta": {}
  },
  "chart": {
    "library": "echarts",
    "type": "line",
    "option": {},
    "rows": []
  },
  "evidence": {
    "servers": ["xquant-indicator-mcp"],
    "tools": ["filter_objects", "select_indicators", "describe_indicators", "calculate_indicators"],
    "requestId": "request-id",
    "definitionVersions": {"gbPDefaultRtnCumulative": 3},
    "dataAsOf": "2025-01-31"
  },
  "warnings": [],
  "errors": [],
  "missingFields": []
}
```

等待用户补充时使用 `status=WAITING_INPUT`，保留已经获得的候选、描述和证据。部分成功时使用 `status=PARTIAL_SUCCESS`。集合查询用 `resolvedObjects` 替代单对象的 `resolvedObject`。

`evidence.tools` 只记录实际调用过的 Tool。对象按名称/代码解析时记录 `search_objects`，按属性、标签、关键词或语义条件筛选时记录 `filter_objects`；未调用的 Tool 不要为了套用示例而写入。

方案路径记录实际调用的 `search_indicator_schemes`、`describe_indicator_schemes`、`calculate_indicator_scheme`，并在证据中增加 `schemeCode`、`moduleRequestId`、`definitionVersion`、`actualRowCount` 与 `parameterSources`。方案结果同时保留 `calculation.rows` 和 `calculation.results`。

## 输出模式

内部始终先生成可审计的结构化结果，再根据 `outputMode` 选择最终展示形态。

### `json`

用于用户明确要求“返回 JSON”“接口返回”或“结构化结果”的场景。返回完整 JSON 兼容对象，不包裹额外叙述；受控失败或等待输入仍保留 `status`、候选、错误和证据。

### `report`

用于用户要求“直接展示结果”“不要 JSON”“给我看图”或“ECharts 图表”，以及未明确要求机器消费的场景。不要返回完整顶层 JSON；按以下顺序输出：

1. 一到三句中文结论；
2. 关键数据表或要点，包括日期范围和单位；
3. ECharts `option`，放在 `json` 代码块中；
4. 对象代码、指标代码、实际调用的 MCP Tool、请求 ID 和参数等执行证据；
5. `warnings`、`errors`、`missingFields` 或口径限制。

纯口径请求不强制生成图表，按“指标定义、公式/口径、数据来源、候选和检索证据”展示。

### `html`

用于用户要求“生成页面”“生成 HTML”或“可打开的图表”的场景。生成本地 HTML 文件，包含摘要、ECharts 图表、标准化数据表、执行证据和限制说明。可以引用公共 ECharts CDN；运行环境不能访问外网时说明限制，并保留可复制的 ECharts `option`。纯口径请求没有数据点时生成口径说明页，不构造虚假系列。

最终回复给出 HTML 文件的可打开路径、摘要和限制，不重复输出完整 JSON。

## 数据归一化

将每个 `results[].points[]` 或 `results[].values[]` 转换为一行：

```json
{
  "indicatorCode": "gbPDefaultRtnCumulative",
  "indicatorName": "组合累计收益率",
  "unit": "%",
  "date": "2025-01-31",
  "value": 1.2,
  "rawValue": 0.012,
  "dimensions": {"endDate": "2025-01-31"}
}
```

规则：

1. `value` 优先取 `displayValue`，否则取 `rawValue`。
2. `date` 依次取 `dimensions.endDate`、点级 `endDate`、`dimensions.begDate`、点级 `beginDate`、`dimensions.date`。
3. 保留全部业务维度，不把维度拼进指标代码。
4. 按日期和业务维度稳定排序。
5. 缺失、`null` 或非数值数据不转换成零。

## 图表选择

| 用户意图或数据形态 | 图表 |
|---|---|
| 时间走势、变化、历史序列 | `line` |
| 对象/指标对比、排名 | `bar` |
| Brinson/Campisi 一级资产类别归因对比 | `bar`（分组柱状图） |
| 方案层级明细 | 表格或树表；必要时附 `tree`，但不得替代可审计行数据 |
| 非负、可相加且明确表示构成 | `pie` |
| 单点指标 | `bar` 或带标注的单点系列 |

多指标处理：

- 相同单位：允许生成多系列图，按日期或分类对齐。
- 不同单位：优先拆成 `charts[]`；确需同图时为每种单位配置独立 `yAxis` 并设置 `series[].yAxisIndex`。
- 不同粒度：不要强行连接为同一折线；分别展示或先请求用户确认聚合方式。
- 饼图：存在负值、时间序列或数值不可相加时禁止使用。
- 方案层级：同名节点必须按 `nodeId` 或完整路径区分，不按 `nodeName` 合并；Brinson 根节点优先展示超额收益、资产配置、个券选择和交叉项。

## ECharts `option`

折线图最低结构：

```json
{
  "title": {"text": "组合累计收益率", "subtext": "I32Q0192"},
  "tooltip": {"trigger": "axis"},
  "legend": {"data": ["组合累计收益率"]},
  "grid": {"left": 48, "right": 28, "top": 72, "bottom": 52},
  "xAxis": {
    "type": "category",
    "boundaryGap": false,
    "data": ["2025-01-01", "2025-01-31"]
  },
  "yAxis": {"type": "value", "name": "%", "scale": true},
  "series": [
    {
      "name": "组合累计收益率",
      "type": "line",
      "smooth": true,
      "showSymbol": false,
      "data": [0.1, 1.2]
    }
  ],
  "dataZoom": [
    {"type": "inside"},
    {"type": "slider", "height": 18}
  ]
}
```

要求：

- 保持纯 JSON，不使用函数、事件处理器、HTML formatter、`eval` 或外部脚本。
- 标题包含指标或比较主题，副标题包含对象代码或名称。
- 坐标轴和 tooltip 使用展示值及正确单位。
- `series[].data` 与分类轴严格对齐；缺失值使用 `null`。
- 数据点过多时使用 `dataZoom`，但不静默丢点；若服务截断数据，在 `warnings` 中说明。
- 同时返回 `rows`，使图表可复核和无障碍降级展示。

## 结论与证据

按以下顺序组织用户可见结果：

1. 一到三句数据结论；
2. 关键数值、日期范围和单位；
3. ECharts 图表配置和标准化行数据；
4. 指标代码、对象代码、MCP Tool、请求 ID、定义版本和数据截止日期；
5. 部分失败、无数据、截断、口径或权限限制。

只描述数据直接支持的变化。不要把相关性写成因果关系，也不要仅凭趋势生成投资建议。
