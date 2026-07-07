# LiCode × Harbor

<p><a href="#english">English</a> · <a href="#中文">中文</a></p>

<a id="english"></a>

Run [LiCode](../) as an agent inside the [Harbor](https://www.harborframework.com/docs/agents)
benchmark framework (containerized coding-agent eval / RL scaffold / SFT data gen).

LiCode is Java; Harbor agent adapters are Python. Integration is two pieces:

1. **LiCode headless mode** (`--print`, in `com.licode.app.LiCode#runHeadless`) — runs one
   instruction to completion with no TUI and `PermissionMode.BYPASS` (no human to approve
   in a container). Prints the final answer to **stdout**; writes a session-JSONL trajectory
   to `.licode/sessions/<id>.jsonl`; exits `0`/`1`.
2. **This Python adapter** (`harbor_licode.agent:LiCodeAgent`, a `BaseInstalledAgent`) —
   installs a JRE + the LiCode jar into the container, shells out to `--print`, and parses
   the trajectory into Harbor's `AgentContext`.

## 1. Build the LiCode fat jar

```bash
cd ..                       # repo root
mvn clean package -DskipTests
# -> target/li-code-1.0-SNAPSHOT.jar   (this is the jar the adapter needs)
```

## 2. Smoke-test headless mode locally (no Harbor)

```bash
mkdir -p /tmp/smoke && cd /tmp/smoke
cat > config.yaml <<'EOF'
providers:
  - name: p
    protocol: openai-compat
    base_url: https://api.deepseek.com/v1
    model: deepseek-v4-flash
EOF
export OPENAI_API_KEY=...     # or ANTHROPIC_API_KEY for protocol: anthropic
java -jar /path/to/li-code-1.0-SNAPSHOT.jar --print "Create hello.txt containing hi" ./config.yaml
# -> stdout: final answer; hello.txt created; .licode/sessions/*.jsonl non-empty; exit 0
```

## 3. Provide the jar + secrets to the container

The adapter expects the jar at `/opt/licode/licode.jar`. Either:
- **bake it into the task image** at that path (preferred), or
- set `LICODE_JAR_URL=<release asset url>` — `install()` will `curl` it.

Provider is configured via env (read in `install()`):
`LICODE_PROTOCOL` (default `anthropic`), `LICODE_BASE_URL`, `LICODE_MODEL`.
The **API key** is taken from the container env (`ANTHROPIC_API_KEY` / `OPENAI_API_KEY`)
— LiCode falls back to these when `config.yaml` has no inline `api_key`. Harbor injects
secrets as env vars.

## 4. Run against a dataset

```bash
pip install -e .                       # or add this dir to PYTHONPATH
harbor run -d "<dataset@version>" --agent harbor_licode.agent:LiCodeAgent
```

## Notes / gotchas

- **cwd must be the task root.** LiCode's `PermissionChecker` L2 path-sandbox only allows
  writes under `projectRoot(=cwd)` or `/tmp`; Harbor runs the agent in the task dir, so this
  is satisfied. L1b still hard-blocks `rm -rf /`-class commands even under BYPASS.
- **Timeout.** `--print` enforces a 30-minute wall-clock cap and prints partial output.
- **`populate_context_post_run` AgentContext API.** The parser (final answer + tool-call list
  from the JSONL) is verified against LiCode's real trajectory schema, but the exact
  `AgentContext` setters may vary by Harbor version — confirm against your install.
- **Provider-agnostic bonus.** LiCode speaks anthropic / openai / openai-compat, so you can
  benchmark the *same* Harbor tasks against DeepSeek / GPT / a local model — something a
  Claude-bound agent can't do.

## Results

Evaluation results, methodology, and honesty caveats: [RESULTS.md](./RESULTS.md).

---

<a id="中文"></a>

## 中文

把 [LiCode](../) 作为 agent，放进 [Harbor](https://www.harborframework.com/docs/agents) 基准框架里运行(容器化的 coding-agent 评测 / RL 脚手架 / SFT 数据生成)。

LiCode 是 Java，Harbor 的 agent 适配层是 Python。集成分两块:

1. **LiCode headless 模式**(`--print`,见 `com.licode.app.LiCode#runHeadless`)—— 无 TUI、`PermissionMode.BYPASS`(容器里没有人来确认)地把一条指令跑到完成。最终答案打到 **stdout**；轨迹以 session-JSONL 写到 `.licode/sessions/<id>.jsonl`;退出码 `0`/`1`。
2. **Python 适配器**(`harbor_licode.agent:LiCodeAgent`,一个 `BaseInstalledAgent`)—— 把 JRE + LiCode jar 装进容器,shell out 到 `--print`，再把轨迹解析成 Harbor 的 `AgentContext`。

### 1. 构建 LiCode fat jar

```bash
cd ..                       # 仓库根目录
mvn clean package -DskipTests
# -> target/li-code-1.0-SNAPSHOT.jar   (适配器需要的 jar)
```

### 2. 本地冒烟测试 headless 模式(不经 Harbor)

```bash
mkdir -p /tmp/smoke && cd /tmp/smoke
cat > config.yaml <<'EOF'
providers:
  - name: p
    protocol: openai-compat
    base_url: https://api.deepseek.com/v1
    model: deepseek-v4-flash
EOF
export OPENAI_API_KEY=...     # protocol: anthropic 则用 ANTHROPIC_API_KEY
java -jar /path/to/li-code-1.0-SNAPSHOT.jar --print "Create hello.txt containing hi" ./config.yaml
# -> stdout 有最终答案;hello.txt 被创建;.licode/sessions/*.jsonl 非空;退出码 0
```

### 3. 给容器提供 jar + 密钥

适配器期望 jar 在 `/opt/licode/licode.jar`。二选一：
- **把它烤进任务镜像**的该路径(推荐),或
- 设 `LICODE_JAR_URL=<release 资产 url>`——`install()` 会 `curl` 下来。

Provider 通过环境变量配置(在 `install()` 里读取):`LICODE_PROTOCOL`(默认 `anthropic`)、`LICODE_BASE_URL`、`LICODE_MODEL`。**API key** 从容器环境变量取(`ANTHROPIC_API_KEY` / `OPENAI_API_KEY`)—— 当 `config.yaml` 没有内联 `api_key` 时 LiCode 回退到它们。Harbor 以环境变量注入密钥。

### 4. 对数据集运行

```bash
pip install -e .                       # 或把本目录加进 PYTHONPATH
harbor run -d "<dataset@version>" --agent harbor_licode.agent:LiCodeAgent
```

### 注意 / 踩坑

- **cwd 必须是任务根目录。** LiCode 的 `PermissionChecker` L2 路径沙箱只允许写 `projectRoot(=cwd)` 或 `/tmp` 之下;Harbor 在任务目录里跑 agent,满足此约束。即使在 BYPASS 下,L1b 仍硬拦 `rm -rf /` 一类命令。
- **超时。** `--print` 强制 30 分钟墙钟上限,并打印部分输出。
- **`populate_context_post_run` 的 AgentContext API。** 解析器(从 JSONL 取最终答案 + 工具调用列表)已对齐 LiCode 真实轨迹 schema,但 `AgentContext` 的具体 setter 可能随 Harbor 版本不同——请对照你的安装确认。
- **协议无关的额外好处。** LiCode 支持 anthropic / openai / openai-compat,所以你能拿**同一批** Harbor 任务对 DeepSeek / GPT / 本地模型做基准——这是绑定 Claude 的 agent 做不到的。

### 评测结果

评测结果、方法学与诚实边界见 [RESULTS.md](./RESULTS.md)。
