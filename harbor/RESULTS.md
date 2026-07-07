# Evaluation — Li Code on SWE-bench-Live

<p><a href="#english">English</a> · <a href="#中文">中文</a></p>

<a id="english"></a>

An end-to-end evaluation of Li Code on **SWE-bench-Live**, plus a paired **ablation** of the context-compression layer and a controlled **cross-framework comparison** against Claude Code.

All numbers are from a small **N = 20** run. Sample-size caveats are stated throughout on purpose — the point here is honest methodology and clean attribution, not a leaderboard score.

## Setup

- **Dataset:** SWE-bench-Live `lite` (frozen split — reproducible and comparable).
- **Instances:** 20, gold-calibrated, sampled across 20 distinct repositories.
- **Model:** `deepseek-chat` (non-reasoning), via DeepSeek's Anthropic-compatible endpoint.
- **Harness:** the official SWE-bench harness (`python-only` branch), `run_evaluation`; *resolved* = `FAIL_TO_PASS` + `PASS_TO_PASS` both green.
- **Generation:** `swebench_live/run_licode.py` runs Li Code headless (`--print`) inside each instance's official Docker image, diffs the working tree into a `model_patch`, and writes `predictions.jsonl`. Li Code itself is unmodified — it reuses the normal headless mode (BYPASS permissions, 30-min cap).

## 1. Context-compression ablation (paired)

Same 20 instances, compression on vs. off (`LICODE_ABLATE=compaction`).

| Group | Resolved | Rate |
|---|---|---|
| baseline (compression **on**) | 7/20 | 35% |
| ablation (compression **off**) | 8/20 | 40% |

- **Paired McNemar:** discordant 0 / 1 → exact **p = 1.0**. No significant difference in resolve rate — the extra 1 is almost certainly LLM randomness (the 95% CI for 35% is ≈ 16–57%).
- **Tokens:** baseline ≈ 11.09M vs. ablation 17.37M → turning compression off raises tokens **+57%**, i.e. compression **cuts ~36% of tokens**. (Token count is unaffected by prompt caching, so this figure is clean.)
- **Conclusion: compression is an outcome-neutral ~36% token reduction.** It drops redundant/stale context that isn't decisive for solving, so it saves cost without costing accuracy.

**Honest boundary.** Resolve% is a single N=20 run with wide CIs. Cost (in RMB) is subtler than tokens: with compression off, history grows monotonically and the prompt prefix stays stable, so many of the "extra" tokens are *cheap prompt-cache hits* — meaning the real cost saving is likely **< 36%**, not more. Tokens are the clean signal; cost is directional.

> The synthetic compression test (`KeyFactRecallTest`) shows 80–90% compression on designed high-inflation inputs, vs. 36% on real tasks. That gap is expected: real deepseek-chat runs are short and rarely cross the L1 (tool result > 50K chars) or L2 (window > 80%) trigger thresholds. Synthetic tests measure the **ceiling under pressure**; real workloads measure the **average under the actual distribution**.

## 2. Cross-framework — Li Code vs. Claude Code

A second controlled axis: **same model, same 20 instances, same harness — only the orchestration framework changes.** Claude Code is driven via `run_claudecode.py` (`claude -p "<problem>" --dangerously-skip-permissions` + `git diff`), structurally aligned with `run_licode.py`.

> ⚠️ **Non-native-config disclaimer.** Claude Code is deeply optimized for Claude models — its system prompt, tool schemas, and thinking format assume a Claude backend. To hold the model constant, this run drives it on `deepseek-chat` via an Anthropic-compat layer, a **non-native configuration**. So **30% is not Claude Code's ceiling**, and the takeaway is **not** "Li Code beats Claude Code." It is: *under identical constrained conditions (same non-native model), Li Code's from-scratch orchestration reaches the same tier as a production agent and is not the bottleneck.*

| Framework | Resolved | Rate |
|---|---|---|
| Li Code | 7/20 | 35% |
| Claude Code | 6/20 | 30% |

- **Agreement 15/20 = 75%**; discordant 3/2 → exact **p = 1.0** (statistically tied). The difference is one instance, with wide CIs.
- Each framework's unique wins are scattered across different difficulties and repositories with no systematic pattern — the signature of noise, not "framework X is reliably better at task type Y."
- **Tokens (directional only, not a clean measurement):** Li Code ≈ 28.46M (clean, boundary-diff) vs. Claude Code 47.86M (observed, contaminated by interrupted/rerun waste). The contaminated figure is only an **upper bound** on Claude Code's cost, so no multiplier claims are made.

## 3. Difficulty stratification

Using the dataset's built-in structural `difficulty` (`files` / `hunks` / `lines`) — a **structural proxy, not semantic difficulty** (which SWE-bench-Live does not label). Binned by hunks (terciles):

| Bucket | n | Li Code | Claude Code |
|---|---|---|---|
| low (smallest fix) | 6 | 50% | 50% |
| mid | 7 | 28.6% | 28.6% |
| high (largest fix) | 7 | 28.6% | 14.3% |

- **Robust takeaway** (holds across hunks- and files-based binning): resolve rate is ~50–60% on the smallest fixes and drops to ~25–29% once a change spans more files/hunks. The bottleneck is **localization on large-change tasks**.
- **Method note:** hunks is a better structural proxy than file count — e.g. a 2-file / 10-hunk / 158-line task is genuinely hard but "looks" small by file count. N is small, so read the trend, not the exact percentages.

## Data-integrity notes

Two disciplines held throughout, both worth stating explicitly:

- **No hand-tuning the comparison.** A reproducible 6/20 for Claude Code sits in the predictions file; reporting anything else would be misconduct. The performance gap is explained through the non-native-config disclaimer above — without changing a single number.
- **Reruns only for infrastructure failures, never real no-ops.** A 0-char patch is either "image didn't pull" (the agent never ran → legitimate rerun) or "the agent ran and produced nothing" (a real result → kept). Rerunning real no-ops would be cherry-picking. Also caught: a `+` in a run-id silently failed 17 otherwise-valid instances at container-build time and masqueraded as 0/20 — traced in `run_instance.log`; an evaluation bug, not an agent failure.

## Reproducing

Generation and scoring live in `swebench_live/` (`run_licode.py`, `run_claudecode.py`, paired McNemar via `aggregate_ablation.py`, `stratify_difficulty.py`). The evaluation runs in a restricted-network WSL environment; the gotchas that actually make it reproducible:

- Use a HuggingFace mirror (`HF_ENDPOINT`) for `load_dataset`.
- **Don't install a JDK inside the instance container** — mount a portable **Temurin 21 JRE** read-only instead (the host JDK's newer glibc won't run in the older instance images).
- Keep `--run_id` and model tags to `[a-zA-Z0-9_.-]` — a `+` breaks Docker container naming and silently fails the whole batch.
- Toggle compression with `LICODE_ABLATE=compaction` (`com.licode.config.Ablation`).

---

<a id="中文"></a>

## 中文

对 Li Code 在 **SWE-bench-Live** 上的端到端评测，外加上下文压缩层的**配对消融**，以及与 Claude Code 的**控制变量跨框架对比**。

所有数字来自一次小样本 **N = 20** 的运行。全文刻意保留样本量的局限说明——这里的重点是**诚实的方法学与干净的归因**，不是刷榜分数。

### 设定

- **数据集**：SWE-bench-Live `lite`(冻结 split,可复现、可对比)。
- **实例**：20 个，gold 校准，跨 20 个不同仓库抽样。
- **模型**：`deepseek-chat`(非推理)，经 DeepSeek 的 Anthropic 兼容端点。
- **评测框架**：官方 SWE-bench harness(`python-only` 分支)`run_evaluation`；*resolved* = `FAIL_TO_PASS` + `PASS_TO_PASS` 全绿。
- **生成侧**：`swebench_live/run_licode.py` 在每个实例的官方 Docker 镜像里以 headless(`--print`)跑 Li Code，把工作区 `git diff` 成 `model_patch`，写入 `predictions.jsonl`。**Li Code 本体不改**，复用现成 headless 模式(BYPASS 权限、30 分钟上限)。

### 1. 上下文压缩配对消融

同一批 20 题,压缩开 vs. 关(`LICODE_ABLATE=compaction`)。

| 组 | resolved | % |
|---|---|---|
| baseline(压缩**开**) | 7/20 | 35% |
| ablation(压缩**关**) | 8/20 | 40% |

- **配对 McNemar**:discordant 0 / 1 → exact **p = 1.0**。成功率无显著差异——多出来的那 1 道几乎肯定是 LLM 随机性(35% 的 95% CI ≈ 16–57%)。
- **Token**:baseline ≈ 11.09M vs. ablation 17.37M → 关掉压缩 token **+57%**，即压缩**砍了 ~36% token**。(token 数不受 prompt 缓存影响,这个数干净。)
- **结论:压缩是"成本中性的 ~36% token 削减"。** 它压掉的是对解题非关键的冗余/陈旧上下文，所以**省钱不掉分**。

**诚实边界**：resolved% 是单次 N=20、CI 很宽。成本(RMB)比 token 微妙:压缩关掉时历史单调增长、前缀稳定，那些"多出来的 token"大多是**便宜的 prompt-cache 命中**，所以真实**成本节省很可能 < 36%**，而非更多。token 是干净信号,成本只作方向性。

> 合成压缩测试(`KeyFactRecallTest`)在专门造的高膨胀输入上显示 80–90% 压缩，真实任务只有 36%——差距符合预期：真实 deepseek-chat 运行又短又轻，很少越过 L1(工具结果 > 50K 字符)或 L2(上下文 > 80%)的触发阈值。合成测试量的是**压力下的能力上限**，真实负载量的是**实际分布下的平均效果**。

### 2. 跨框架:Li Code vs. Claude Code

控制变量的第二根轴：**同模型、同 20 题、同 harness,唯一变量是编排框架。** Claude Code 经 `run_claudecode.py` 驱动(`claude -p "<problem>" --dangerously-skip-permissions` + `git diff`)，与 `run_licode.py` 结构对齐。

> ⚠️ **非原生配置免责声明。** Claude Code 是**为 Claude 系列深度优化**的产品——它的 system prompt、工具 schema、thinking 格式都假设后端是 Claude。为保证控制变量(同模型),本轮让它跑 `deepseek-chat` + Anthropic 兼容层，属于**非原生配置**。因此这里的 **30% 不代表 Claude Code 的能力上限**，结论**也不是**"Li Code 强于 Claude Code"，而是：*在同等受限条件下(同一非原生模型)，Li Code 的自研编排达到了生产级 agent 的同一水位、没有成为瓶颈。*

| 框架 | resolved | % |
|---|---|---|
| Li Code | 7/20 | 35% |
| Claude Code | 6/20 | 30% |

- **一致率 15/20 = 75%**；discordant 3/2 → exact **p = 1.0**(统计持平)。差的是 1 道题，CI 极宽。
- 两边各自的"独有胜绩"散落在不同难度、不同仓库,无系统性规律——这是**噪声**的典型特征,而非"某框架在某类任务稳定更强"。
- **Token(仅方向性,非干净测量)**：Li Code ≈ 28.46M(干净,边界差值)vs. Claude Code 47.86M(观测值,含中断/重跑污染)。污染值只是 Claude Code 成本的**上界**，因此不作任何倍数主张。

### 3. 难度分层

用数据集自带的结构 `difficulty`(`files` / `hunks` / `lines`)——这是**结构代理，非语义难度**(SWE-bench-Live 未提供语义标注)。按 hunks 三分位分桶:

| 桶 | n | Li Code | Claude Code |
|---|---|---|---|
| low(最小修复) | 6 | 50% | 50% |
| mid | 7 | 28.6% | 28.6% |
| high(最大修复) | 7 | 28.6% | 14.3% |

- **稳健结论**(hunks / files 两种分桶一致):最小修复上 ~50–60%,一旦改动跨更多文件/hunk 就掉到 ~25–29%。瓶颈在**大改动任务的定位**。
- **方法学要点**:hunks 比文件数更好的结构代理——比如一个 2 文件 / 10 hunk / 158 行的任务其实很难，但按文件数"看起来"很小。N 小，只看趋势，别报精确百分比。

### 数据诚信

全程坚持了两条，值得明说:

- **拒绝手调对比指标。** predictions 里躺着可复现的 Claude Code 6/20；报别的数就是学术不端。性能差距靠上面的非原生配置免责声明讲清，**不动一个数字**。
- **只重跑基础设施失败,绝不重跑真实 no-op。** 0 字符补丁分两种："镜像没拉下来"(agent 没跑 → 合理重跑)vs "agent 跑完没产出"(真实结果 → 保留)。重跑真实 no-op = 挑对自己有利的采样。另外还抓到：一个 run-id 里的 `+` 让 17 个本来正常的实例在建容器那步静默失败、伪装成 0/20——从 `run_instance.log` 查出，是评测 bug 而非 agent 能力问题。

### 复现

生成与评分在 `swebench_live/`(`run_licode.py`、`run_claudecode.py`,配对 McNemar 用 `aggregate_ablation.py`、`stratify_difficulty.py`)。评测跑在受限网络的 WSL 里;真正让它可复现的几个坑:

- `load_dataset` 用 HuggingFace 镜像(`HF_ENDPOINT`)。
- **别在实例容器里装 JDK**——改成只读挂载便携 **Temurin 21 JRE**(宿主 JDK 的 glibc 太新,跑不进旧实例镜像)。
- `--run_id` 和 model tag 只用 `[a-zA-Z0-9_.-]`——一个 `+` 会破坏 Docker 容器命名,静默整批失败。
- 压缩开关:`LICODE_ABLATE=compaction`(`com.licode.config.Ablation`)。
