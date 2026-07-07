# LiCode × SWE-bench-Live

<p><a href="#english">English</a> · <a href="#中文">中文</a></p>

<a id="english"></a>

Evaluate LiCode's problem-solving on **SWE-bench-Live** (Track A: baseline
`resolved%`), and run a **controlled-variable ablation** of context compaction
(Track B).

## Prerequisites
- **WSL2 + Docker Desktop** (SWE-bench-Live images are `linux/amd64`).
- LiCode fat jar: `cd ../.. && mvn clean package -DskipTests` → `target/li-code-1.0-SNAPSHOT.jar`.
- `pip install datasets` (the runner loads the HF dataset).
- An API key in env: `ANTHROPIC_API_KEY` (or `OPENAI_API_KEY` for openai/openai-compat).
- The **official SWE-bench-Live evaluation harness** installed (its GitHub repo).

## ⚠️ Confirm before first run
Verify these in `run_licode.py` (env-overridable) against
<https://swe-bench-live.github.io/> and the project's GitHub repo:
1. `SWEBL_DATASET` / `SWEBL_SPLIT` — exact HF dataset id + split.
2. `SWEBL_IMAGE_PATTERN` — per-instance Docker image name pattern.
3. `TESTBED` — repo path in the image (SWE-bench standard is `/testbed`).
4. The final `run_evaluation` module path/flags printed by the runner.

Instance fields used (`instance_id`, `repo`, `base_commit`, `problem_statement`,
`patch`, `test_patch`, `FAIL_TO_PASS`, `PASS_TO_PASS`) are the standard SWE-bench
schema, which SWE-bench-Live inherits.

## Workflow

```bash
# 0) pick a fixed N=20–30 subset → instances.txt (see that file's header)

# 1) Track A baseline: generate predictions (compaction ON = default)
export ANTHROPIC_API_KEY=...   LICODE_MODEL=claude-opus-4-8   LICODE_PROTOCOL=anthropic
python run_licode.py --subset instances.txt --out preds.baseline.jsonl

# 2) run the OFFICIAL SWE-bench-Live eval on preds.baseline.jsonl
#    (the runner prints the exact command; produces a report json)

# 3) Track B ablation: SAME subset, compaction OFF
LICODE_ABLATE=compaction python run_licode.py --subset instances.txt --out preds.ablate.jsonl
#    → eval it the same way → ablation report json

# 4) paired comparison + McNemar + cost table
python aggregate_ablation.py \
    --baseline base.report.json --ablation abl.report.json \
    --baseline-traj <baseline .licode/sessions dir> \
    --ablation-traj <ablation .licode/sessions dir> \
    --out ../../target/ablation-swebench.json
```

## How it works
- `run_licode.py` — for each instance: `docker run` the image, install JRE + jar,
  `java -jar licode.jar --print "<problem_statement>"` in `/testbed`, capture
  `git diff` as `model_patch`, write predictions JSONL. **LiCode is unchanged** —
  it uses the existing headless `--print` mode (`PermissionMode.BYPASS`, 30-min cap).
- `LICODE_ABLATE=compaction` — forwarded into the container; read by
  `com.licode.config.Ablation`, which makes `Agent.agentLoop` skip L1
  (`ToolResultBudget`) + L2 (`ContextCompactor`). Default (unset) = all ON.
- `aggregate_ablation.py` — 2×2 contingency on resolved/unresolved over the
  identical subset + exact McNemar p-value + coarse trajectory cost totals.

## Notes
- Keep `instances.txt` committed so baseline and ablation score the **same**
  instances (paired test requires it).
- Prefer `effort:low`/deterministic sampling to reduce LLM variance; budget
  permitting, run each config k times and average.
- The `harbor/` sibling dir has a Harbor `BaseInstalledAgent` adapter — a
  complementary general-eval track that reuses the same `--print` mechanics.
- Results and honesty caveats: [../RESULTS.md](../RESULTS.md).

---

<a id="中文"></a>

## 中文

在 **SWE-bench-Live** 上评测 LiCode 的解题能力(Track A:baseline `resolved%`),并对上下文压缩做**控制变量消融**(Track B)。

### 前置条件
- **WSL2 + Docker Desktop**(SWE-bench-Live 镜像是 `linux/amd64`)。
- LiCode fat jar:`cd ../.. && mvn clean package -DskipTests` → `target/li-code-1.0-SNAPSHOT.jar`。
- `pip install datasets`(runner 会加载 HF 数据集)。
- 环境变量里的 API key:`ANTHROPIC_API_KEY`(openai/openai-compat 则 `OPENAI_API_KEY`)。
- 装好**官方 SWE-bench-Live 评测 harness**(其 GitHub 仓库)。

### ⚠️ 首次运行前确认
对照 <https://swe-bench-live.github.io/> 和项目 GitHub,核对 `run_licode.py` 里这几项(可用环境变量覆盖):
1. `SWEBL_DATASET` / `SWEBL_SPLIT` —— 确切的 HF 数据集 id + split。
2. `SWEBL_IMAGE_PATTERN` —— 每实例的 Docker 镜像名模式。
3. `TESTBED` —— 镜像里的仓库路径(SWE-bench 标准是 `/testbed`)。
4. runner 打印出的最终 `run_evaluation` 模块路径/参数。

用到的实例字段(`instance_id`、`repo`、`base_commit`、`problem_statement`、`patch`、`test_patch`、`FAIL_TO_PASS`、`PASS_TO_PASS`)是标准 SWE-bench schema,SWE-bench-Live 沿用之。

### 工作流

```bash
# 0) 选一个固定的 N=20–30 子集 → instances.txt(见该文件头部)

# 1) Track A baseline:生成 predictions(压缩默认开)
export ANTHROPIC_API_KEY=...   LICODE_MODEL=claude-opus-4-8   LICODE_PROTOCOL=anthropic
python run_licode.py --subset instances.txt --out preds.baseline.jsonl

# 2) 对 preds.baseline.jsonl 跑官方 SWE-bench-Live 评测
#    (runner 会打印确切命令;产出 report json)

# 3) Track B 消融:同一子集,压缩关掉
LICODE_ABLATE=compaction python run_licode.py --subset instances.txt --out preds.ablate.jsonl
#    → 同法评测 → 消融 report json

# 4) 配对对比 + McNemar + 成本表
python aggregate_ablation.py \
    --baseline base.report.json --ablation abl.report.json \
    --baseline-traj <baseline .licode/sessions 目录> \
    --ablation-traj <ablation .licode/sessions 目录> \
    --out ../../target/ablation-swebench.json
```

### 原理
- `run_licode.py` —— 每个实例:`docker run` 镜像、装 JRE + jar、在 `/testbed` 里 `java -jar licode.jar --print "<problem_statement>"`、把 `git diff` 抓成 `model_patch`、写 predictions JSONL。**LiCode 本体不改**,复用现成 headless `--print` 模式(`PermissionMode.BYPASS`、30 分钟上限)。
- `LICODE_ABLATE=compaction` —— 透传进容器,由 `com.licode.config.Ablation` 读取,让 `Agent.agentLoop` 跳过 L1(`ToolResultBudget`)+ L2(`ContextCompactor`)。默认(不设)= 全开。
- `aggregate_ablation.py` —— 在同一子集上对 resolved/unresolved 做 2×2 列联表 + 精确 McNemar p 值 + 粗粒度轨迹成本合计。

### 注意
- **提交并保留 `instances.txt`**,让 baseline 和消融评测的是**同一批**实例(配对检验的前提)。
- 优先用 `effort:low`/确定性采样降低 LLM 方差;预算允许时每个配置跑 k 次取平均。
- 同级 `harbor/` 目录有一个 Harbor `BaseInstalledAgent` 适配器——复用同一套 `--print` 机制的通用评测轨道。
- 评测结果与诚实边界:[../RESULTS.md](../RESULTS.md)。
