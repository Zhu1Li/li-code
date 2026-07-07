---
name: fix-tests
description: 运行测试并自动修复失败，直到全绿或到上限（隔离子 Agent 中执行）
allowedTools:
  - Bash
  - ReadFile
  - WriteFile
  - Edit
  - Glob
  - Grep
  - RecallFailures
  - RecordFailure
mode: fork
context: none
---

# 任务

你在一个**隔离子 Agent** 里运行。目标：把项目测试跑到全绿。自动循环"跑 → 分析 → 修 → 重跑"，直到全部通过或达到上限。

## 步骤

1. **检测构建工具并跑测试**，按优先级：
   - `pom.xml` → `mvn test`
   - `build.gradle` → `gradle test`
   - `package.json` → `npm test`
   - `go.mod` → `go test ./...`
   捕获完整输出。

2. **全绿则收工**：报告通过数量，结束。

3. **有失败时，先查历史**：从失败信息里提取关键词（失败的测试名、异常类型、报错关键字），调 `RecallFailures`，看有没有相似的历史失败可参考。**这一步必须在动手改之前做。**

4. **分析每个失败**，区分两类（判据同 test 技能）：
   - **代码 bug**：断言期望值正确、实际值错，说明源码有问题 → 改源码。
   - **测试 bug**：断言期望值本身不对、或测试设置有误 → 改测试。

5. **修复**：只改必要的地方，遵循项目现有代码风格（如有 `code-style` 记忆，遵循它）。

6. **重跑测试**，回到第 2 步。**最多循环 8 轮**——每一轮都要真正改动后再重跑，不要原样重试同一条命令（那样毫无意义）。

7. **收尾**：
   - 修到全绿 → 调 `RecordFailure` 沉淀这次经验：`keywords`（测试名/异常/关键字）、`root_cause`（真正的根因）、`fix`（怎么修的）、可选 `test_name` / `exception`。每个独立的失败根因记一条。然后报告：修了什么、改了哪些文件。
   - 到 8 轮仍未全绿 → **如实汇报**：还有哪些测试没过、你判断卡在哪、下一步建议。**不要假装修好了。**

$ARGUMENTS
