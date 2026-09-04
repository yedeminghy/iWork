# 默认信息 契约

## 使用规则

当用户问题缺少指标参数值时，按适用范围从下列默认值中补齐。Tool 顶层请求参数与逐指标参数是两个独立作用域，不得混用。

适用规则：

- 指标默认值、指标计算接口默认值和公共默认值仅用于补齐 `indicators[].parameters`；仅当 `describe_indicators.parameterSchema` 声明了同名参数，且用户显式参数和合法语义均未提供值时使用。
- 参数代码必须精确匹配；例如 `bmCode` 的默认值不能自动用于名称不同的 `benchmarkCode`。
- 指标参数默认值须通过指标参数类型、枚举和可见性校验，不得覆盖固定参数或隐藏参数。
- 按以下优先级匹配：**指标默认值 > 指标计算接口默认值 > 公共默认值**。同一范围内出现重复参数时，必须拒绝歧义配置，不得按表格顺序猜测。
- 用户明确指定了值时，以用户值为准；在执行证据中记录默认值来源及范围：`DEFAULT_INFORMATION_INDICATOR`、`DEFAULT_INFORMATION_INTERFACE` 或 `DEFAULT_INFORMATION_PUBLIC`。

## Tool 顶层显式覆盖参数

本节参数没有 Skill 默认值，不参与指标参数补全。

| Tool | 参数代码 | Skill 默认值 | 说明 |
|---|---|---|---|
| `calculate_indicators` | `_mergeDimE` | 无 | 仅当用户显式指定全局覆盖值时写入 Tool 请求顶层；用户未指定时必须省略，由 MCP Server 按指标 `interfaceCode` 自动解析 |

## 公共默认值

适用于所有指标补齐参数；优先级低于指标计算接口默认值和指标默认值。

|         参数代码 | 参数名称   | 默认值                   | 说明                |
|-------------:|--------|-----------------------|-------------------|
|   indexCode	 | 市场指数代码	 | 20221027094499000031	 | 沪深300指数，风险指标使用	   |
| riskFreeCode | 无风险利率代码	 | 647146                | 1年定存利率(DEPO_F01Y) |
|      calCode | 交易日历代码	 | NULL              | 自然日历，按字符串"NULL"传参             |
|      bmCode | 基准代码	 | 20221027094499000031  | 沪深300指数，风险指标使用             |
|      bmCodeList | 基准代码列表	 | 20221027094499000031  | 沪深300指数             |
|      confidence | 置信度	  | 0.95                  |   标准置信度           |
|      nodeList | 分组列表	  | 11318              |   根节点 ID           |

## 指标计算接口默认值

适用于指定指标计算接口提供的所有指标；优先级低于指标默认值、高于公共默认值。

| 指标计算接口 | 参数代码 | 默认值 | 说明 |
|---|---|---|---|
| calcBrinsonAsset | nodeList | 31170 | Brinson大类资产归因分组树 |
| calcBrinsonAsset | scenarioId | aab5403d25844ae3acfffd672ff918b2 | 场景ID |
| calcBrinsonStandard | nodeList | 13107 | Brinson股票归因分组树 |
| calcDetailPLAttribution | attributionType | PL_DETAIL | 损益归因明细 |

## 指标默认值

仅适用于指定指标代码的计算；优先级最高。

| 指标代码 | 参数代码 | 默认值 | 说明 |
|---|---|---|---|
| gbAssetAllocationBrinsonA | attributionType | BRINSON_ASSET | Brinson（大类资产）归因 |
