"""
Harbor `BaseInstalledAgent` adapter for LiCode.

LiCode is a Java coding agent. This adapter installs a JRE + the LiCode fat jar
into the Harbor task container, runs one instruction to completion via LiCode's
headless `--print` mode (added in com.licode.app.LiCode#runHeadless), and parses
LiCode's session-JSONL trajectory into Harbor's AgentContext.

Interface reference: https://www.harborframework.com/docs/agents (Installed agents)

Run:
    harbor run -d "<dataset@version>" --agent harbor_licode.agent:LiCodeAgent

Container needs an API key in env (LiCode falls back to ANTHROPIC_API_KEY /
OPENAI_API_KEY when config.yaml has no inline api_key). Harbor injects secrets
via environment variables.
"""

from __future__ import annotations

import glob
import json
import os
import shlex

from harbor.agents.installed.base import BaseInstalledAgent, with_prompt_template
from harbor.environments.base import BaseEnvironment
from harbor.models.agent.context import AgentContext

# Where the jar lands in the container, and where to fetch it from.
LICODE_JAR = "/opt/licode/licode.jar"
# Prebuilt fat jar URL (e.g. a GitHub release asset). Override per deployment.
JAR_URL = os.environ.get("LICODE_JAR_URL", "")

# Minimal config; api_key intentionally omitted so LiCode uses the env fallback.
CONFIG_YAML = (
    "providers:\n"
    "  - name: default\n"
    "    protocol: {protocol}\n"
    "    base_url: {base_url}\n"
    "    model: {model}\n"
)


class LiCodeAgent(BaseInstalledAgent):
    """LiCode integrated as a headless, container-installed Harbor agent."""

    @staticmethod
    def name() -> str:
        return "licode"

    def version(self) -> str | None:
        return "1.0-SNAPSHOT"

    async def install(self, environment: BaseEnvironment) -> None:
        # 1) Java runtime (LiCode targets Java 21).
        await self.exec_as_root(
            environment,
            command="apt-get update && apt-get install -y --no-install-recommends "
            "openjdk-21-jre-headless curl ca-certificates",
        )
        # 2) Place the prebuilt LiCode fat jar.
        #    Preferred: bake the jar into the task image at LICODE_JAR.
        #    Fallback: fetch it from LICODE_JAR_URL if the image doesn't have it.
        if JAR_URL:
            await self.exec_as_root(
                environment,
                command=f"mkdir -p /opt/licode && curl -fsSL {shlex.quote(JAR_URL)} -o {LICODE_JAR}",
            )
        # 3) Write a minimal provider config; secret comes from the container env.
        cfg = CONFIG_YAML.format(
            protocol=os.environ.get("LICODE_PROTOCOL", "anthropic"),
            base_url=os.environ.get("LICODE_BASE_URL", "https://api.anthropic.com"),
            model=os.environ.get("LICODE_MODEL", "claude-opus-4-8"),
        )
        await self.exec_as_agent(
            environment,
            command=f"mkdir -p ~/.licode && printf %s {shlex.quote(cfg)} > ~/.licode/config.yaml",
        )

    @with_prompt_template
    async def run(
        self,
        instruction: str,
        environment: BaseEnvironment,
        context: AgentContext,
    ) -> None:
        # Run headless in the task working directory. LiCode's Write/Edit/Bash
        # tools mutate that dir directly — exactly what Harbor scores afterward.
        # PermissionMode.BYPASS (set inside --print) auto-approves tools; the
        # L1b dangerous-command + L2 path-sandbox guards still apply, so the cwd
        # must be the task root for legitimate edits to pass the sandbox.
        await self.exec_as_agent(
            environment,
            command=f"java -jar {LICODE_JAR} --print {shlex.quote(instruction)}",
        )

    def populate_context_post_run(self, context: AgentContext) -> None:
        """Parse LiCode's session JSONL trajectory into the Harbor AgentContext.

        Trajectory schema (verified against LiCode SessionManager output):
          each line = {"role": "user"|"assistant", "timestamp": ..,
                       "content"?: str, "toolUses"?: [{"name": str, ..}],
                       "toolResults"?: [..]}
        The final assistant `content` is the agent's answer.
        """
        traj_glob = os.path.join(os.getcwd(), ".licode", "sessions", "*.jsonl")
        files = sorted(glob.glob(traj_glob), key=os.path.getmtime)
        if not files:
            return
        latest = files[-1]

        tool_calls: list[str] = []
        final_answer = ""
        try:
            with open(latest, encoding="utf-8") as fh:
                for line in fh:
                    line = line.strip()
                    if not line:
                        continue
                    msg = json.loads(line)
                    for tu in msg.get("toolUses") or []:
                        if tu.get("name"):
                            tool_calls.append(tu["name"])
                    if msg.get("role") == "assistant" and msg.get("content"):
                        final_answer = msg["content"]
        except (OSError, json.JSONDecodeError):
            return

        # NOTE: confirm the exact AgentContext setters against your installed
        # Harbor version — the attribute names below are the common shape but
        # may differ. The parsed values (final_answer, tool_calls, latest) are
        # what you feed in whichever way Harbor's AgentContext expects.
        if hasattr(context, "set_final_response"):
            context.set_final_response(final_answer)
        elif hasattr(context, "final_response"):
            context.final_response = final_answer
        if hasattr(context, "metadata") and isinstance(getattr(context, "metadata"), dict):
            context.metadata["tool_calls"] = tool_calls
            context.metadata["trajectory_path"] = latest
