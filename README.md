# Procurement Intake Demo（采购统一入口 + 需求合并）

员工用自然语言提采购需求，DeepSeek 把需求映射到公司的标准品类清单，追问缺失信息，输出可入库的标准化请求；
审批通过后进入需求池，按规则合并成批量采购单，展示合并带来的节省。

技术栈：Java 21 · Spring Boot 3.5 · MongoDB · DeepSeek（OpenAI 兼容接口）· 原生 HTML/JS 前端（英文）

---

## 一、运行前准备

| 需要 | 说明 |
| --- | --- |
| JDK 21 | 推荐 Eclipse Temurin 21。命令行 `java -version` 应显示 21 |
| Maven 或 IntelliJ IDEA | IDEA（社区版即可）自带 Maven，最省事 |
| MongoDB | 二选一：安装 MongoDB Community Server（默认端口 27017），或装了 Docker 的话在项目目录运行 `docker compose up -d` |
| DeepSeek API Key | 见下一步 |

## 二、填写 API Key（只改这一处）

1. 复制 `secrets.properties.example`，改名为 `secrets.properties`（放在项目根目录，和 `pom.xml` 同级）。
2. 把里面的 `sk-REPLACE_ME` 换成你的 DeepSeek key：
   ```
   llm.api-key=sk-xxxxxxxxxxxxxxxx
   ```
`secrets.properties` 已写进 `.gitignore`，不会被提交。也可以改用环境变量 `DEEPSEEK_API_KEY`。
模型名和地址在 `src/main/resources/application.yml` 的 `llm:` 段（默认 `deepseek-v4-pro`，`https://api.deepseek.com`）。

## 三、启动

**IntelliJ IDEA**：File → Open → 选择本文件夹 → 等 Maven 下载依赖 → 运行 `ProcurementApplication`。
运行配置里的 Working directory 要是项目根目录（默认就是），否则读不到 `secrets.properties`。

**命令行**（已安装 Maven）：
```
mvn spring-boot:run
```
打开 http://localhost:8080 。首次启动会自动把 `seed/` 里的模拟数据写进 MongoDB。

跑单元测试：`mvn test`

## 四、演示脚本（约 5 分钟）

1. **Pool & orders 页** → 点 *Load sample requests*：7 个"其他同事"的请求进入需求池。
   注意看：Brother 硒鼓因为交期来不及，立即放行；其余显示 Hold 和原因。
2. **New request 页**，右上角选 *Ana Lopez (Financial Analyst)*，输入：
   `Our team laptops are too old, I need two new ones`
   → 助手根据岗位推断"标准办公本"（琥珀色 = AI 推断），只追问缺的日期和理由。
3. 回答后点 *Confirm and submit*。回到 Pool 页：Madrid 的标准办公本从 3 台变 5 台，跨过 5 台阶梯价，出现节省金额。
4. 再试两个：选 *Marta Sanchez*，输入 `the printer next to the kitchen is out of toner`
   → 员工不用知道硒鼓型号，系统按资产表找到打印机，再按兼容表匹配硒鼓。
   选 *Javier Ruiz*，输入 `three backend developers start on December 1st` → 一句话展开成整套入职设备。
5. **Pool 页** 把日期改成下周二 → *Run consolidation*：按周窗口合并下单，显示每张采购单省了多少、每个请求等了几天。
6. **非标需求**：选 *Laura Gomez*，输入 `IT needs a Mac by December 15 for training ML models on Apple Metal; it must have a strong GPU`
   → 面板列出每条要求与目录数据的比对结果（满足 / 不满足 / 目录未说明，由代码判定，不是 AI）；
   系统自动换成满足更多硬性要求的档位；费用记到 IT 部门。点 *Send to a buyer as special request*。
7. **Buyer desk 页**：采购员看到员工原话、未满足的要求和参考品，填写询价结果后 *Approve and order*，系统直接生成采购单（不进合并池）。
8. **Audit log 页**：每次模型调用（用途、耗时、token 用量、原始输出）和每个状态变化都有记录。

`Reset demo` 会清空请求和订单，并重新加载 `seed/` 数据。

## 五、架构

模块化单体：每个包是一个模块，模块之间只通过 Service 接口和领域事件通信，以后哪块需要单独扩容可以直接拆出去。

```
web            渠道适配：Web 聊天和页面接口。企业 IM 机器人是另一个适配器，调用同一个 IntakeService
intake         需求受理：分类 → 预填 → 模型抽取 → 规范化校验 → 追问/确认 → 入库
llm            模型网关：唯一调用模型的地方，结构化输出、一次 JSON 修复重试、记录每次调用
catalog        品类注册表：品类模板（要收集哪些字段）、商品档位与阶梯价、岗位套装
resolution     字段 → 商品行：按品类策略（直接映射 / 资产兼容 / 岗位套装）确定 SKU，纯规则
rules          规则引擎：交期、预算阈值、无法匹配等校验，不用模型
context        员工上下文：模拟 HR 和资产系统，系统已知的信息不问员工
request        采购请求与状态机：SUBMITTED → APPROVED → POOLED → ORDERED，每次变化发事件
approval       审批桩：订阅"已提交"事件自动批准；换成真实 OA 只改这里
consolidation  需求池与合并调度：三个放行策略 + 加急通道，原子认领防止重复下单
audit          审计：订阅所有事件 + 记录模型调用
admin          种子数据加载、演示工具
```

**请求流转**
```
员工一句话 → [模型] 分类选模板 → 系统预填（工位、默认值）→ [模型] 按模板抽取字段
  → [规则] 规范化 + 校验（SKU/资产/日期/数量不合法一律不入库）→ 缺什么追问（最多 3 轮，超过转人工采购）
  → 员工确认 → PurchaseRequest 入库 ──事件──> 审批 ──事件──> 进入需求池
  → 合并调度（每周窗口 / 达到最优阶梯价 / 到最晚下单日；加急直接下单）→ 合并采购单 → ERP 接口（桩）
```

**关键设计取舍**
- 员工提的条件（"必须 macOS""要独显"）由模型拆成结构化条件，再由代码逐条比对目录里的可比对属性（`attributes`），结果分为满足 / 不满足 / 目录未说明。同样的输入永远得到同样的判定。
- 请求里保存员工原话（一字不改），AI 的摘要和选型理由单独存放并标明来源。
- 特殊请求的处理时限来自配置 `intake.buyer-response-days`，模型不允许自己估计时间。
- DeepSeek 默认开启思考模式，很慢；`llm.thinking: disabled` 关闭（默认），需要时可改为 enabled。
- 模型只做"理解和填空"，问什么由品类模板决定，SKU 由规则和数据确定，缺什么由代码计算。模型输出不可信的部分全部被规范化层拦截。
- 每个字段标注来源（员工说的 / AI 推断 / 系统已知），审批人能看到哪些是猜的。
- 用 MongoDB：品类模板和商品规格字段各不相同，文档模型最合适。后续做走账、付款时，订单和账务建议放关系型数据库（PostgreSQL），按数据特性选存储。
- 合并下单不依赖多文档事务：用条件原子更新（只有仍在池中的行能被认领），并发运行也不会重复下单，本地单机 MongoDB 即可。

## 六、怎么扩展

| 想做的事 | 改哪里 |
| --- | --- |
| 新增商品或档位 | `seed/catalog_items.json`，然后 Reset demo |
| 新增品类（字段不同） | `seed/categories.json` 加一个模板；若映射方式是新的，再加一个 `LineResolver` 实现 |
| 新增校验规则 | 在 `rules` 包加一个实现 `ValidationRule` 的 `@Component` |
| 新增合并放行条件 | 加一个实现 `ReleasePolicy` 的 `@Component` |
| 换模型厂商 | 改 `application.yml` 的 `llm:`；协议不兼容 OpenAI 时，新写一个 `LlmClient` 实现 |
| 接真实审批 / ERP | 替换 `ApprovalStub` / `LoggingPurchaseOrderGateway` |
| 下一步：走账流程 | 新模块订阅 `OrderReleased`、`RequestOrdered` 事件，不改现有模块 |

## 七、主要接口

| 方法 | 路径 | 作用 |
| --- | --- | --- |
| POST | `/api/intake/conversations` | 开始对话 `{employeeId}` |
| POST | `/api/intake/conversations/{id}/messages` | 发消息 `{text}` |
| POST | `/api/intake/conversations/{id}/confirm` | 确认提交 |
| GET | `/api/requests` | 采购请求列表 |
| GET | `/api/consolidation/pool?asOf=yyyy-MM-dd` | 需求池预览（不下单） |
| POST | `/api/consolidation/run?asOf=yyyy-MM-dd` | 执行合并 |
| GET | `/api/consolidation/orders` | 合并采购单 |
| GET | `/api/buyer/requests` | 等待采购员处理的特殊请求 |
| POST | `/api/buyer/requests/{id}/approve`、`/reject` | 采购员批准（填写询价结果并直接下单）或驳回 |
| GET | `/api/audit` | 审计日志 |
| POST | `/api/demo/sample-requests`、`/api/demo/reset` | 演示工具 |
