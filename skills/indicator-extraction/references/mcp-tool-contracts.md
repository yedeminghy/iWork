# MCP Tool 契约

## 目录

- 对象类型
- xquant-indicator-mcp/search_objects
- xquant-indicator-mcp/filter_objects
- xquant-indicator-mcp/select_indicators
- xquant-indicator-mcp/search_indicators
- xquant-indicator-mcp/describe_indicators
- xquant-indicator-mcp/get_indicator_parameter_options
- xquant-indicator-mcp/calculate_indicators
- xquant-indicator-mcp/search_indicator_schemes
- xquant-indicator-mcp/describe_indicator_schemes
- xquant-indicator-mcp/get_indicator_scheme_parameter_options
- xquant-indicator-mcp/calculate_indicator_scheme
- 状态与失败处理

## 对象类型

| 值 | 类型 |
|---:|---|
| 0 | 组合 |
| 1 | 计划 |
| 2 | 投管人机构 |
| 3 | 投资经理 |

## `xquant-indicator-mcp/search_objects`

缺少对象代码时调用。

建议输入：

```json
{
  "query": "安心一号组合",
  "objectType": 0
}
```

从 `items`、`objects`、`results` 或 `data` 数组读取候选。兼容以下字段：

- 代码：`code`、`objectCode`、`portCode`、`portfolioCode`、`id`
- 名称：`name`、`objectName`、`portName`、`portfolioName`、`label`
- 类型：`type`、`objectType`、`portType`

优先读取 `resolution` 和 `requiresConfirmation`。`requiresConfirmation=true` 时必须展示候选并等待确认；否则只在唯一候选或唯一精确匹配时自动选择。

## `xquant-indicator-mcp/filter_objects`

按受控属性、标签、名称关键词或语义条件筛选对象集合。它补充 `search_objects`，不替代按代码或名称进行的单一对象解析。

建议输入：

```json
{
  "objectType": 0,
  "question": "查询重点监控的固收类组合",
  "semanticClauses": [
    {"id": "c1", "term": "重点监控", "operatorHint": "EQ", "polarity": "POSITIVE", "logicalOperator": "AND"},
    {"id": "c2", "term": "固收类", "operatorHint": "EQ", "polarity": "POSITIVE", "logicalOperator": "AND"}
  ],
  "includeAttributes": ["invStyle"],
  "includeTags": true,
  "pageSize": 50,
  "includeTotal": true
}
```

关键输入：

- `objectType` 必填，只支持 `0` 至 `3`。
- 至少提供 `keyword`、`attributeFilters`、`tagFilters`、`semanticTerms` 或 `semanticClauses` 之一。
- `semanticClauses` 支持正向 AND、同属性/标签的 OR、EQ/NE 否定，以及数值和日期比较；服务端将自然语言字段和值解析为受控过滤条件，不接受模型编造的内部代码。
- `resolveOnly=true` 仅完成语义解析和过滤编译，不查询对象快照；读取 `resolvedClauses` 和 `appliedFilters`。
- `semanticTerms` 必须由调用方拆成正向原子条件。多个原子条件按交集应用；不要用它表达“或”或否定。
- `attributeFilters[]` 使用受控 `code`、`operator` 和 `values`。支持的操作符包括 `EQ`、`NE`、`IN`、`PREFIX`、`EXISTS`、`GT`、`GTE`、`LT`、`LTE`、`BETWEEN`，实际可用范围以属性定义为准。
- `tagFilters` 使用 `all`、`any`、`none`；一期只支持 `objectType=0`。
- 只有代码和值已经由显式参数、受控候选或 Tool 返回结果确认时，才发送 `attributeFilters` 或 `tagFilters`；不得自行构造属性代码、标签代码或枚举值。
- `pageSize` 最大 100。后续分页必须原样保留筛选条件，并传入上次响应的 `nextCursor`。
- 一期不支持 `semanticObjectQuery`，不要把整句问题直接作为一个语义对象查询参数。

成功输出示例：

```json
{
  "status": "SUCCESS",
  "objectType": 0,
  "matchMode": "SEMANTIC_TO_FILTER",
  "completeness": "EXACT",
  "items": [
    {
      "objectCode": "I32Q0192",
      "objectName": "示例组合",
      "objectType": 0,
      "attributes": {},
      "tags": [],
      "matchedBy": ["attribute:invStyle"]
    }
  ],
  "page": {
    "pageSize": 50,
    "returned": 1,
    "hasMore": false,
    "nextCursor": null,
    "totalCount": 1
  },
  "resolvedTerms": [],
  "unresolvedTerms": [],
  "appliedFilters": {},
  "warnings": [],
  "meta": {
    "requestId": "request-id",
    "snapshotVersion": 1,
    "dataAsOf": "2026-08-06"
  }
}
```

处理规则：

- 关联对象筛选只允许以下映射：`计划(1) → 组合(0): planCode`、`投管人机构(2) → 组合(0): invManager`、`投管人机构(2) → 投资经理(3): currentOrganization`。先用 `search_objects` 取得唯一限定对象代码，再把该代码作为 `attributeFilters` 的值；不得直接使用关联对象名称。
- 当前没有 `投资经理(3) → 组合(0)` 的受控关系映射；不得降级为查询该经理所在机构的组合。
- `status=WAITING_INPUT` 且 `code=AMBIGUOUS_FILTER`：展示 `ambiguousTerms[].term` 及其 `candidates`，等待用户确认后使用受控候选重试。
- `status=SUCCESS`：只把 `completeness=EXACT` 的 `items` 视为精确过滤结果。
- `totalCount=1`，或当前仅返回一项且 `hasMore=false` 时，可将该项解析为唯一计算对象。
- 纯对象名单明确要求“所有/全部”时，最多自动读取 10 页或 1000 个对象；超限仍有后续页时返回已获取数量和 `nextCursor`，不得声称已返回全部。
- 返回多个同类型对象且用户要求计算指标时，按游标有界收集完整集合后传给 `calculate_indicators.objectSet`；默认上限为 10 页、100 个对象，超限需缩小范围。
- `totalCount=null` 不表示没有结果；检查 `items`、`hasMore` 和 `warnings`。
- `NO_FILTER_CANDIDATE`、`UNKNOWN_ATTRIBUTE`、`UNKNOWN_TAG`、`CONFLICTING_FILTERS`、`OBJECT_ACCESS_DENIED` 或 `SNAPSHOT_UNAVAILABLE` 均按受控失败处理，不降级为不受约束的名称搜索。

## `xquant-indicator-mcp/select_indicators`

一般查询优先使用本 Tool。它会拆分多指标诉求、检索 KB-XINDEX、选择目录中可调用指标、推断支持的频率参数，并报告歧义或缺参。

输入：

```json
{
  "question": "查询组合 I32Q0192 在 2025 年 1 月的日收益率和单位净值",
  "metricQueries": ["日收益率", "单位净值"],
  "topKPerMetric": 10,
  "maxIndicators": 10,
  "minConfidence": 0.58
}
```

多对象输入将 `object` 替换为：

```json
{"objectSet": {"codes": ["I32Q0192", "I32Q0193"], "type": 0}}
```

`question` 必填；其余字段可选。不要把非指标词放入 `metricQueries`。

关键输出：

```json
{
  "status": "SUCCESS",
  "selectedIndicatorCodes": ["gbPDefaultRtn"],
  "selectedIndicatorRequests": [
    {"code": "gbPDefaultRtn", "parameters": {"freqType": "D"}}
  ],
  "selectedIndicators": [
    {
      "query": "日收益率",
      "code": "gbPDefaultRtn",
      "name": "日收益率",
      "confidence": 0.98,
      "parameters": {"freqType": "D"},
      "missingParameters": [],
      "ready": true
    }
  ],
  "unresolvedMetrics": [],
  "requiresInput": false
}
```

可能状态为 `SUCCESS`、`PARTIAL_SUCCESS` 或 `WAITING_INPUT`。逐项处理 `unresolvedMetrics[].reason`，常见原因包括 `NO_CANDIDATE`、`LOW_CONFIDENCE`、`AMBIGUOUS`、`MAX_INDICATORS_EXCEEDED` 和 `MISSING_REQUIRED_PARAMETERS`。

## `xquant-indicator-mcp/search_indicators`

纯指标口径请求（定义、算法、公式、口径或数据来源）必须先调用本 Tool；在需要展示候选指标或 `select_indicators` 不可用时也可使用。一般指标计算路径不要与 `select_indicators` 重复检索。

```json
{
  "query": "累计收益率",
  "topK": 10
}
```

仅使用返回目录中的指标信息，不凭名称构造指标代码。纯口径请求不需要解析计算对象，也不调用 `calculate_indicators`；多个候选无法唯一确定时返回候选列表并等待用户确认。

## `xquant-indicator-mcp/describe_indicators`

在计算前批量校验指标及参数。

```json
{
  "indicatorCodes": ["gbPDefaultRtnCumulative"]
}
```

指标参数定义与对象类型无关；旧调用携带的 `objectType` 会被服务端忽略。

读取 `indicators[]` 中的 `code`、`name`、`description`、`interfaceCode`、`unit`、`valueType`、`parameterSchema`、`dynamicOptionParameters`、`dateRangeLimitDays` 和 `definitionVersion`。对 `notFound` 中的代码停止计算。

参数合并优先级：用户显式参数 > 合法语义推断 > [默认信息](default-information.md) 中按参数代码精确匹配的默认值 > `get_indicator_parameter_options` 动态选项 > Schema 默认值 > 服务端默认值。契约默认值命中后不调用动态选项 Tool，但仍必须通过对应指标的 `parameterSchema`，且不得覆盖固定参数或隐藏参数。

## `xquant-indicator-mcp/get_indicator_parameter_options`

只为 `describe_indicators` 标记为动态候选的缺失参数调用。

当前 Tool Schema：

```json
{
  "indicatorCode": "gbPDefaultRtnCumulative",
  "parameterName": "calCode"
}
```

向用户展示 `options[].value` 和 `options[].label`。动态选项不可用时请求用户提供合法值，不要执行或展示底层 SQL。

## `xquant-indicator-mcp/calculate_indicators`

输入：

```json
{
  "object": {"code": "I32Q0192", "type": 0},
  "dateRange": {"begin": "2025-01-01", "end": "2025-01-31"},
  "indicators": [
    {
      "code": "gbPDefaultRtnCumulative",
      "parameters": {}
    }
  ]
}
```

约束：

- 日期为闭区间，格式为 `yyyy-MM-dd`，且 `begin <= end`。
- `object` 与 `objectSet` 必须且只能提供一个；`objectSet.codes` 去重后最多 100 个同类型对象，最多 50 个指标。
- `_mergeDimE` 是 `calculate_indicators` 的可选顶层字符串参数，只用于用户显式指定全局覆盖值，不属于任何单个指标的参数。用户未显式指定时，Skill 必须省略该参数；不得从默认信息中补值，不得将其放入 `indicators[].parameters`，也不使用 `describe_indicators.parameterSchema` 校验它。
- `_mergeDimE` 省略时，MCP Server 根据每个指标的 `interfaceCode` 读取服务端接口配置；未命中时使用服务端全局默认值。不同最终值会参与参数签名并自动拆分为不同上游批次，Skill 不拆分 Tool 调用。
- 服务端按对象和指标二维拆批，向指标平台重复发送同名 `portCode`，并按响应中的对象代码维度聚合结果。
- 每个 `indicators[]` 元素维护独立 `parameters`。
- `indicators[].parameters` 不发送 `describe_indicators` 未声明的参数；此约束不适用于 `_mergeDimE` 等 Tool 顶层请求参数。
- MCP 服务负责校验、分组、拆分和调用指标平台；Skill 不做本地指标计算。

关键输出：

```json
{
  "status": "SUCCESS",
  "object": {"code": "I32Q0192", "type": 0},
  "dateRange": {"begin": "2025-01-01", "end": "2025-01-31"},
  "results": [
    {
      "indicatorCode": "gbPDefaultRtnCumulative",
      "indicatorName": "组合累计收益率",
      "unit": "%",
      "dataStatus": "AVAILABLE",
      "points": [
        {
          "rawValue": 0.012,
          "displayValue": 1.2,
          "dimensions": {"endDate": "2025-01-31"}
        }
      ],
      "warnings": []
    }
  ],
  "errors": [],
  "meta": {
    "requestId": "request-id",
    "definitionVersions": {"gbPDefaultRtnCumulative": 3},
    "source": "xquant-indicator-platform",
    "mergeDimensions": [
      {
        "indicatorCode": "gbPDefaultRtnCumulative",
        "interfaceCode": "calcPortfolioReturn",
        "value": "PBE",
        "source": "GLOBAL_DEFAULT"
      }
    ]
  }
}
```

`meta.mergeDimensions[].source` 为 `REQUEST_OVERRIDE`、`INTERFACE_CONFIGURATION` 或 `GLOBAL_DEFAULT`。最终报告和执行证据以该字段为准，不根据请求是否携带 `_mergeDimE` 推测实际值。

兼容服务版本使用 `values` 代替 `points`；两者归一化规则相同。

## `xquant-indicator-mcp/search_indicator_schemes`

按代码、名称、别名或自然语言说明检索 `KB-XSCHEME` 中已发布且技术定义可执行的方案。

```json
{"query":"Brinson大类资产归因","objectType":0,"topK":10}
```

读取 `resolution=EXACT|UNIQUE|AMBIGUOUS|NONE` 与 `requiresConfirmation`。只有 `EXACT` 或 `UNIQUE` 且无需确认时继续；精确代码也必须通过发布状态、技术目录和对象类型校验。

## `xquant-indicator-mcp/describe_indicator_schemes`

```json
{"schemeCodes":["BrinsonAssetAttribution"]}
```

读取 `schemes[].parameterSchema`、`effectiveParameters`、`automaticParameters`、`supportedObjectTypes`、`maxObjects`、`outputDimensions`、`outputIndicators` 和 `definitionVersion`。`parameterSchema.required` 只表示调用方需要补充的可见参数，不照搬上游 `mustInput`。`PARAM_VALUE_TYPE` 为空的参数明细按可覆盖默认值处理。

## `xquant-indicator-mcp/get_indicator_scheme_parameter_options`

预留的受控选项查询契约：

```json
{"schemeCode":"BrinsonAssetAttribution","parameterName":"parentNodeList","pageSize":50}
```

一期默认方案路径不依赖本 Tool。返回 `SCHEME_OPTION_PROVIDER_UNAVAILABLE` 时，不读取数据库、不执行 SQL；若参数确为可见必填且无默认值，则请求用户补充。`SELECT` 参数后续可从 `tsys_dict_mult_lang` 对应的受控接口取枚举，`ACCTTREE`、`MULTI_GROUPTREE` 参数后续可从 `txqa_grp_tree`、`txqa_grp_tree_node` 对应的受控接口取分组树；Skill 不直接访问这些表。

## `xquant-indicator-mcp/calculate_indicator_scheme`

```json
{
  "object": {"code":"I32Q0192","type":0},
  "dateRange": {"begin":"2025-01-01","end":"2025-01-31"},
  "scheme": {
    "code":"BrinsonAssetAttribution",
    "parameters": {},
    "requestedOutputCodes":["gbAssetAllocationBrinsonA","gbSecuritySelectionBrinsonA"]
  }
}
```

约束：

- `object` 与 `objectSet` 必须且只能提供一个；方案最多 50 个同类型对象。
- 多对象由 MCP 向平台重复发送同名 `portCode`；单、多对象均发送 `portCalcType=single`。
- MCP 始终发送与 `calculate_indicators` 相同来源的服务端 `appKey`，使用方案代码作为 `moduleNo`，不发送 `_idxCodeList`。
- 调用方不能覆盖保留参数，也不发送平台自动参数 `relativeLayer`、`attributionType`。
- `scenarioId` 仅在调用方提供时发送一次；不同时发送 `scenarioIds`，缺省时交由平台处理。
- `portCodeList`、`nodeList`、`parentNodeList` 等 LIST 参数按同名查询参数重复序列化。
- 仅发送当前方案 `parameterSchema` 声明的方案参数；其他参数可能属于该方案关联指标，但不能推断为所有方案的公共参数。
- `requestedOutputCodes` 只能是方案输出子集；方案仍完整执行，响应阶段再投影。

输出同时包含 `rows[].dimensions/values` 和 `results[].points`。缺失字段保持缺失或 `null`，不补零；`meta.actualRowCount` 取实际数组长度，不使用上游 `count` 单独判断无数据。

## 状态与失败处理

- MCP 协议错误：停止当前步骤并返回受控失败。
- `FAILED`：不要生成成功图表。
- `PARTIAL_SUCCESS`：保留成功的 `results`，同时完整呈现 `errors`。
- `dataStatus=NO_DATA`：保留指标元数据，但不把缺失点补成零。
- 权限错误：不重试为其他对象或其他用户上下文。
- 参数校验错误：回到参数补全步骤，不能删除必填参数后重试。
