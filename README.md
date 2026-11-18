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
6. **先查内部资源**：输入 `I need access to a Mac by December 15 to train a small Core ML prototype with Apple Metal. Remote access is fine, the model fits within 8 GB memory, and this is not urgent.`
   → 系统把运行本应用的 Mac 当作公司共享服务器，实时读取 CPU/GPU 负载、芯片、内存和 Metal 能力；模型返回 `CAN_FULFILL / CANNOT_FULFILL / NEEDS_INFO` 和理由。点 *Use company Mac server · no purchase* 后会话以 `FULFILLED_INTERNAL` 结束，不创建采购申请。
7. **非标需求**：如果员工说必须带实体 Mac 去客户现场，共享服务器不能满足便携性，继续走标准目录或 *Send to a buyer as special request*。
8. **Buyer desk 页**：采购员看到员工原话、未满足的要求和参考品，填写询价结果后 *Approve and order*，系统直接生成采购单（不进合并池）。
9. **Audit log 页**：每次模型调用（用途、耗时、token 用量、原始输出）和每个状态变化都有记录。

`Reset demo` 会清空请求和订单，并重新加载 `seed/` 数据。

## 受理 Agent（借鉴 Codex CLI 的循环）

员工的每条消息都会触发一个 agent 循环（`intake/AgentIntakeService.java`）：
把完整历史和工具列表发给模型 → 执行模型要求的工具调用 → 把工具结果（**包括错误**）追加到历史 → 再调用模型，
直到模型用文字作答，或某个工具需要员工回应（选择题、确认提案）。每条消息最多 `intake.max-agent-steps` 步。

问什么、问几步、先查什么，都由模型自己规划；系统只提供工具，并守住四条底线：

| 底线 | 实现 |
| --- | --- |
| 事实只来自工具 | 价格、规格、资产、实时负载只能通过工具获得 |
| "满不满足"由代码判定 | `check_requirements` 用比对器逐条比对，结果为满足 / 不满足 / 数据未说明 |
| 表单由系统校验 | `propose_*` 校验不通过时，把每条问题返回给模型修正（不在背后改数据） |
| 提交必须员工确认 | 模型只能"提议"，员工点 Confirm 才会创建请求或占用资源 |

| 工具 | 作用 |
| --- | --- |
| `update_plan` | 模型的计划，页面右侧实时显示 |
| `search_company_resources` | 先调配再采购：闲置笔记本、共享资源，以及这台 Mac 的实时 CPU/GPU 负载（仅在 macOS 上运行时提供） |
| `search_catalog` | 标准目录：品类表单字段、商品规格、可比对属性、阶梯价、交期、岗位套装 |
| `check_requirements` | 员工条件 vs 某个商品或资源，确定性比对 |
| `ask_user` | 1–3 道选择题，界面自动加"其他" |
| `propose_internal_use` / `propose_standard_request` / `propose_special_request` | 三种结果的提案，校验通过后显示确认卡 |

下游的审批、合并池、下单、采购员工作台仍是状态机，因为它们记录的是单据在公司里的流转，需要审计。

## 审批矩阵与资金池

**角色（`seed/staff.json`）**：每个部门压缩成一到两级——直属经理（预算负责人）、部门负责人、财务主管、CFO、IT 资产经理、采购员、品类经理。页面右上角切换身份（演示用请求头 `X-Staff-Id`，生产环境换成单点登录）。

**审批矩阵（政策版本里的审批规则表，初始版本 `policy/policy-rules.json`；下表是 2026.11 版的内容）**

| 条件 | 审批人 |
|---|---|
| 目录内、≤ €1,000、预算内，且无 IT/云平台/数据安全必审事项 | 自动批准 |
| 其余 | 直属经理 |
| IT 品类（笔记本、入职套装） | + IT 资产经理 |
| > €5,000 | + 部门负责人、财务主管 |
| > €25,000 | + CFO |
| 超出部门季度预算 | + 财务主管（软拦截：可以提交，由财务决定） |
| 特殊请求 | 先由经理批准"需求"，采购员定价后再按实际金额走一遍矩阵 |

职责分离：任何人不能审批自己的请求，同一人不在同一请求里占两个审批步骤，遇到时自动上升一级。

**资金池（`procurement.budget`）**：每季度 €100,000，分配到各部门。

预算余额和账本现保存在同一个季度账户文档中，通过版本条件更新一次性提交；重试不会重复记账，承诺和释放互斥。兼容单机 MongoDB，无需改 Docker 拓扑。旧的分离账本在首次访问账户时导入，旧集合保留；升级前必须停止旧版应用，不能混跑新旧写入者。迁移不自动纠正旧版本已经产生的错账，需另行核对。当前嵌入式季度账本适合演示规模；大规模部署前需要按 MongoDB 单文档 16 MB 限制设计分片账本或事务方案。

审查回归：先启动独立 MongoDB（本机端口 27028），运行 `mvn '-Dtest=*Test,ReviewRegressionIT' test`。测试只操作 `procurement_review_20261115` 数据库。身份认证保持 demo，两项生产身份隔离测试明确标为跳过，其余包含真实 MongoDB、故障注入和并发测试。普通 `mvn test` 不包含这些 `*IT` 集成用例。
- 全部审批通过 → **预留**（原子操作：两个请求抢最后一笔钱，只有一个成功；另一个自动转财务主管决定）
- 下单 → **承诺**，按实际价格；拼单省下的差额自动退回部门预算
- 驳回 / 撤销 → **释放**
- 每笔变动记流水，id 即幂等键，重试或重复点击不会扣两次

**财务信息隔离（程序层面，不靠提示词）**
- Agent 只能通过 `BudgetGate` 得到 `WITHIN_BUDGET / OVER_BUDGET / NOT_DISCLOSED`，接口里没有任何金额。
- 检查的金额由系统按目录价格计算，模型不能自选金额去试探；每次对话最多 3 次判定，之后一律 `NOT_DISCLOSED`。
- `BudgetBarrierTest` 扫描 intake 模块源码：凡引用预算模块中除 `BudgetGate`/`BudgetVerdict` 以外的类，构建失败。
- 预算金额接口 `/api/budgets` 只对财务（全部门）和预算负责人（本部门）开放，其他身份返回 403。

## 云服务：框架协议下的标准流程

参考真实做法（斯坦福、加州大学、英国政府与 AWS 的 OGVA 等）：公司先和云厂商签企业协议，员工不再自己"买服务器"，而是在协议下申请一个云账号或订阅，按用量每月分摊到成本中心。

- 类目 `CLOUD`：AWS、Azure 两家协议供应商；地区可选 Spain / Other EU / Hong Kong；字段包括预计月费、期限、数据分级、技术负责人。
- 审批：云平台负责人始终参与（不自动批准）；数据为 Confidential/Restricted，或非公开数据出欧盟 → 加 IT 安全与数据保护；金额按合同总值（月费 × 月数）套矩阵。
- 预算：只预留本季度剩余月份的费用；合同总值用于审批。
- 下单：按请求单独生成调用单（call-off），不进合并池。
- 协议外的云厂商：agent 只记录需求和理由，不调研、不推荐，交给采购决定（战略采购流程待定）。

## 采购政策与三条路线

政策原文：`src/main/resources/policy/procurement-policy.md`（agent 用 `read_policy` 引用条文）。规则本身是数据，见下一节「政策即数据」；下面描述的是初始版本 2026.11。

- **路线判定**（`PolicyRouter`）：先复用 → 是否战略级 → 是否目录内。战略级硬触发：合同全周期 > €25,000、框架品类用协议外供应商、新服务 > 12 个月、唯一来源、新供应商涉及 Restricted 数据。
- **路线 A 标准**：审批矩阵 + 预算，采购员不参与。
- **路线 B 采购员寻源**：经理批准需求 → 采购员受理 → 录入报价（≤ €3,000 一份，以上三份或写明原因）→ 定标 → 按实际金额审批；定标 > €25,000 自动转战略级。
- **路线 C 战略级**：品类经理页面（Strategic）四个结论：纳入现有协议 / 询价招标 / 谈新框架协议 / 驳回。经理审批和采购员寻源时都可以点"Strategic review"手动提交。agent 对战略级采购只记录，不调研、不推荐（程序拒绝候选型号）。
- **拆单检查**：30 天内同部门、同品类（目录外：同供应商）的申请合计跨过门槛时，审批按合计金额。

## 政策即数据：规则表、步骤组合、版本与回放

政策变了不改代码。架构分四层：

| 层 | 是什么 | 在哪里 | 变更方式 |
|---|---|---|---|
| 事实 | 系统认识的词汇：合同金额、合同期、数据分级、唯一来源、品类、是否超预算…… | `policy/Facts.java` | 新增事实要写代码（得有人去收集它） |
| 规则 | 决策表：路线判定、审批人、自动批准、报价份数。每行是"条件 → 结论 + 理由 + 条款号" | 政策版本（MongoDB `policy_versions`，初始版本来自 `policy/policy-rules.json`） | Policy 页面起草 → 检查 → 发布 |
| 步骤 | 积木：`APPROVAL`（带阶段）、`SOURCING`（带负责人）、`STRATEGIC_REVIEW`、`BUDGET_RESERVE`、`FULFIL` | `flow/*StepHandler.java`，每块一个类 | 新增一种步骤 = 新增一个 `StepHandler` 实现 |
| 路线 | 积木的有序组合，例如 采购员寻源 = 需求审批 → 寻源 → 金额审批 → 预算预留 → 下单 | 政策版本的 `routes` | Policy 页面，和规则一起发布 |

- **引擎**：`FlowEngine` 不认识任何一条路线的名字，只执行"下一步"的积木，遇到要等人的步骤就停。`RuleEngine` 只做条件求值（十种运算符）。
- **版本**：每张申请单记录提交时的政策版本（`policyVersion`）和它的步骤列表（`flow`），之后一直按这个版本走完。政策变更不会重判在途的单子。已发布的版本不能修改，只能出新版本。
- **发布前三道检查**（`PolicySimulator`，Policy 页面的 Save & check）：
  1. 结构与底线：未知的事实、角色、步骤类型、路线引用都会被指出。
  2. 标准案例：每个版本自带一组案例（`cases`），规则必须全部判对。改了门槛而没改案例，发布会被拒绝。
  3. 历史回放：用新规则重判所有历史申请，列出结果会变的每一张（路线、审批人、报价份数）。发布时必须确认看过这份清单。
- **不可配置的底线**（写在代码里，改规则表也绕不过去）：自己不能批自己的单、一人只占一个审批环节；预算数字不进模型；每条路线必须先过审批规则、再预留预算、最后才能下单；必须保留一条战略评审路线（审批人和采购员随时可以升级）；自动批准必须要求预算内；超预算必须有一条"知情批准"规则；战略级必须先和员工沟通。
- **演示**：Policy 页面 → 以品类经理身份新建草稿 `2026.12` → 把 `R-VALUE` 的 25000 改成 50000（同时改对应的标准案例）→ Save & check 看回放 → Publish。之后同样的 €30,000 申请走标准路线，而之前提交的仍按 2026.11 处理。也可以在 `BUYER_SOURCED` 路线里插入一个 `{"type":"APPROVAL","stage":"COMPLIANCE"}`，再加一条该阶段的审批规则，就多了一个合规审查环节。
- **生产环境**：决策表和流程可以换成标准引擎（DMN / BPMN，如 Camunda、Flowable）；这里的四层划分、版本和回放检查保持不变。发布还应加双人复核（起草人和发布人不同）。

## 寻源：按行报价、价格核对、订单纠正

- **一张申请，多行明细**：一项需求（25 个工位）是一张申请、一次需求审批，门槛按合计；需求里的不同物品（升降桌、椅子）是不同的行。受理助手提交时按行拆分，每行有自己的硬性要求和参考价（来自对话中读过的网页）。
- **按行报价、按行定标**：每份报价必须注明覆盖哪一行、具体是什么、证据（链接/报价单号）；报价描述不再预填。报价份数按整单定标额定，每一行都要满足，按不同供应商计数。错误的报价可以撤回（保留备查）。各行可以定给不同供应商，每行生成一张采购单，各自收货。
- **价格核对**：定标价与参考价相差超过 40%（政策参数 `priceDeviationPercent`）时，采购员必须说明；审批人看到偏差和说明。之前"把椅子的价格当成一套桌椅"的错误，现在会在这里被拦住。
- **批准需求 ≠ 批准价格**：定标后预算负责人要再批一次（审批规则只在同一阶段内跳过已批的人）。
- **订单纠正**：Buyer desk 的"Ordered, not delivered yet"里，采购员可以取消有误的订单并重新寻源：通知供应商、退回预算承诺（预算流水记一笔 CANCEL）、价格审批作废、待收货记录作废。已有货物入库的，只能在收货时退货。
- **退回采购员**：金额审批时，审批人除了批准、驳回、提交战略评审，还可以"Send back to buyer"：需求没问题，定标有问题。申请回到原采购员的寻源阶段，顶部显示退回原因和原定标；对该价格的审批作废但保留记录，需求审批保留；重新定标后从预算负责人开始重新审批。驳回仍是终止，流程条会在停下的那一步显示 ✗。
- **采购员身份**：Buyer desk 右上角选身份；接口只允许采购员（战略寻源时为品类经理）操作，报价记录是谁录入的。
- **收货页**显示定标的报价、证据链接和员工的硬性要求，收货人可以对照实物。

## 收货与资产入库

采购单发出后，流程不停在"已下单"：

1. **下单即预登记**：每张采购单生成一张收货单（Receiving 页面），型号、供应商、单价、交付地点、为谁买，都从订单带过来。
2. **入库门槛（政策第十三节，规则表 `assets`）**：IT 设备一律入库，由 IT 资产经理收货；其他物品单价超过 €100 才入库，由行政收货；耗材只收货不入库；云服务不收货。
3. **AI 的两处帮助，都不做决定**：
   - 目录外物品，模型从固定清单里选它是什么（家具、显示器……），规则再决定是否入库、谁收货；收货人可以改。
   - 收货时上传送货单照片（`llm.vision-model`，默认 `deepseek-flash`；`deepseek-v4-pro` 不能读图）或粘贴文字，模型抄录并和订单比对；代码检查单号、数量、序列号是否重复或已在资产库中，再预填表单，收货人确认。
4. **确认收货**：IT 设备必须一个单位一个序列号（扫码枪逐个扫）；型号与订单不符必须写说明，否则退回；退回的数量仍算待到货。单位按需求日期先后分给各个申请：只订一台给自己的，资产直接分配给申请人；为多人订的（如新员工），资产标为 RESERVED，到 Assets 页面再发给具体的人。RESERVED 资产不会被受理 agent 推荐复用。
5. **申请变为"已到货"（RECEIVED）**，My requests 显示到货进度和资产号；预算从"已承诺"转为"已支出"。真实公司要等发票与订单、收货单三单匹配后才算支出，这里没有发票环节，用收货代替。

资产库原来每次启动都从种子文件重新加载，现在只在为空时加载一次（Reset demo 仍会重置），收货登记的资产重启后不会丢失。

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
fulfillment    内部履约：把当前 Mac 模拟为共享服务器，采集实时 CPU/GPU 快照，采购前先判断能否内部满足
request        采购请求与状态机：SUBMITTED → APPROVED → POOLED → ORDERED，每次变化发事件
policy         政策即数据：事实词汇、规则求值、版本库、发布前检查与历史回放
flow           路线引擎与步骤积木：按政策版本里的步骤列表推进申请
receiving      收货与资产入库：下单预登记、AI 分类与读送货单、确认收货、资产登记与发放
approval       审批：按规则表生成审批链（职责分离在代码里），审批人决定或升级
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
| 改门槛、审批人、报价份数、路线顺序 | Policy 页面出一个新政策版本，不改代码 |
| 新增一种流程步骤 | `flow` 包加一个实现 `StepHandler` 的 `@Component`，然后在政策版本的路线里使用 |
| 规则要用一个新的事实 | `Facts` 加一项，并让表单或 agent 收集它 |
| 接真实审批 / ERP | `ApprovalStepHandler` 改为调用 OA；替换 `LoggingPurchaseOrderGateway` |
| 下一步：走账流程 | 新模块订阅 `OrderReleased`、`RequestOrdered` 事件，不改现有模块 |

## 七、主要接口

| 方法 | 路径 | 作用 |
| --- | --- | --- |
| POST | `/api/intake/conversations` | 开始对话 `{employeeId}` |
| POST | `/api/intake/conversations/{id}/messages` | 发消息 `{text}` |
| POST | `/api/intake/conversations/{id}/confirm` | 确认提交 |
| GET | `/api/requests` | 采购请求列表 |
| GET | `/api/internal-resources/mac-server` | 当前 Mac 的硬件配置和实时 CPU/GPU 负载 |
| POST | `/api/intake/conversations/{id}/fulfill-internally` | 使用已匹配的内部资源，不创建采购单 |
| GET | `/api/consolidation/pool?asOf=yyyy-MM-dd` | 需求池预览（不下单） |
| POST | `/api/consolidation/run?asOf=yyyy-MM-dd` | 执行合并 |
| GET | `/api/consolidation/orders` | 合并采购单 |
| GET | `/api/buyer/requests` | 等待采购员处理的特殊请求 |
| POST | `/api/buyer/requests/{id}/approve`、`/reject` | 采购员批准（填写询价结果并直接下单）或驳回 |
| GET | `/api/audit` | 审计日志 |
| POST | `/api/demo/sample-requests`、`/api/demo/reset` | 演示工具 |
