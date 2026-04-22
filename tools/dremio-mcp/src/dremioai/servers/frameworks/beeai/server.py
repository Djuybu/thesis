#
#  Copyright (C) 2017-2025 Dremio Corporation
#
#  Licensed under the Apache License, Version 2.0 (the "License");
#  you may not use this file except in compliance with the License.
#  You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
#  Unless required by applicable law or agreed to in writing, software
#  distributed under the License is distributed on an "AS IS" BASIS,
#  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#  See the License for the specific language governing permissions and
#  limitations under the License.
#

from typing import Union, Optional, Annotated, Any, AsyncGenerator, List, Dict
from beeai_framework.agents.react.agent import ReActAgent, ReActAgentRunOutput
from beeai_framework.backend.chat import ChatModel
from beeai_framework.backend.types import ChatModelParameters
from beeai_framework.tools.mcp_tools import MCPTool
from beeai_framework.backend.constants import ProviderHumanName, ProviderName
from beeai_framework.memory.sliding_memory import SlidingMemory, SlidingMemoryConfig
from beeai_framework.emitter import Emitter, EventMeta
from beeai_framework.errors import FrameworkError
from beeai_framework.agents.types import AgentExecutionConfig
from beeai_framework.template import PromptTemplate, PromptTemplateInput

from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client
from pydantic import BaseModel

from dremioai.config import settings

from typer import Typer, Option
from pathlib import Path
from shutil import which
from os import environ
from enum import StrEnum, auto
from rich import print as pp

import readline
from rich.prompt import Prompt
import asyncio
from dremioai.log import logger
from contextlib import asynccontextmanager


class AgentEvent(StrEnum):
    ERROR = auto()
    RETRY = auto()
    UPDATE = auto()
    START = auto()
    SUCCESS = auto()
    FINISH = auto()
    NEW_TOKEN = auto()


class ReactAgentWithSession:
    def __init__(self, agent: ReActAgent, session: Optional[ClientSession] = None):
        self.agent = agent
        self.session = session
        self.execution = AgentExecutionConfig(
            max_retries_per_step=30, total_max_retries=100, max_iterations=50
        )

    def process_events(self, data: Any, event: EventMeta) -> str:
        try:
            match event.name:
                case AgentEvent.ERROR.value:
                    if hasattr(data, "error"):
                        return FrameworkError(data.error).explain()
                    return str(data)
                case AgentEvent.RETRY.value:
                    return "Retrying..."
                case AgentEvent.UPDATE.value:
                    return f"Agent({data.update.key}): {data.update.parsed_value}"
                case AgentEvent.START.value:
                    return "Starting new iteration..."
                case AgentEvent.SUCCESS.value:
                    return "Success"
                case AgentEvent.FINISH.value:
                    return "Finished"
                case _:
                    return event.name
        except KeyError:
            return f"Unknown event: {event.path}, {event.name}"

    def observer(self, emmitter: Emitter):
        # emmitter.on("*.*", lambda data, ev: pp(self.process_events(data, ev)))
        pass

    async def run(self, prompt: str) -> ReActAgentRunOutput:
        return await self.agent.run(prompt=prompt, execution=self.execution).observe(
            self.observer
        )


@asynccontextmanager
async def create_react_agent(
    chat_model: Optional[
        Union[ProviderHumanName, ProviderName, ChatModel]
    ] = "Anthropic",
    chat_model_parameters: Optional[ChatModelParameters] = None,
) -> AsyncGenerator[ReactAgentWithSession]:
    """
    Create a react agent.

    :param chat_model: The chat model to use.
    :param chat_model_parameters: The chat model parameters.
    :return: A react agent.
    """
    if not isinstance(chat_model, ChatModel):
        chat_model = ChatModel.from_name(
            chat_model,
            (
                chat_model_parameters
                if isinstance(chat_model_parameters, ChatModelParameters)
                else ChatModelParameters(temperature=0)
            ),
        )

    def construct_stdio_params(
        beeai: Optional[settings.BeeAI] | None = None,
    ) -> StdioServerParameters:
        if beeai is not None and beeai.mcp_server is not None:
            args = beeai.mcp_server.args if beeai.mcp_server.args else []
            env = beeai.mcp_server.env if beeai.mcp_server.env else {}
            return StdioServerParameters(
                command=beeai.mcp_server.command, args=args, env=env
            )

    def create_agent(
        chat_model: ChatModel,
        tools: Optional[List[MCPTool]] = None,
        system_prompt: Optional[Dict[Any, PromptTemplate]] = None,
    ) -> ReActAgent:
        return ReActAgent(
            llm=chat_model,
            tools=tools,
            templates=system_prompt,
            memory=SlidingMemory(
                SlidingMemoryConfig(size=settings.instance().beeai.sliding_memory_size)
            ),
        )

    async def create_system_prompt(
        mcp_session: ClientSession,
    ) -> Dict[Any, PromptTemplate] | None:
        tool_prompts = await mcp_session.list_prompts()
        for tp in tool_prompts.prompts:
            if tp.name == "system_prompt":
                if (
                    pv := await mcp_session.get_prompt(tp.name)
                ) is not None and pv.messages:
                    content = "\n".join(
                        pm.content.text
                        for pm in pv.messages
                        if pm.content.type == "text"
                    )
                    sp = PromptTemplate(
                        PromptTemplateInput(
                            schema=type("SystemPrompt", (BaseModel,), {}),
                            template=content,
                        )
                    )
                    return {"user": sp}

    if (server_params := construct_stdio_params(settings.instance().beeai)) is not None:
        async with (
            stdio_client(server_params) as (read, write),
            ClientSession(read, write) as mcp_session,
        ):
            await mcp_session.initialize()
            tools = await MCPTool.from_client(mcp_session)

            system_prompt = await create_system_prompt(mcp_session)
            yield ReactAgentWithSession(
                create_agent(chat_model, tools=tools, system_prompt=system_prompt),
                mcp_session,
            )
    else:
        yield ReactAgentWithSession(create_agent(chat_model), None)


async def do_chat(model: str, ps: Prompt):
    async with create_react_agent(model) as agent_with_session:
        while True:
            try:
                user_input = ps.ask(">>> ")
                if user_input.lower() == "q":
                    break
            except EOFError:
                break

            response = await agent_with_session.run(user_input)
            pp(response.result.text)


app = Typer()


@app.command(context_settings=dict(help_option_names=["-h", "--help"]))
def main(
    config_file: Annotated[
        Optional[Path],
        Option("-c", "--cfg", help="The config yaml for various options"),
    ] = settings.default_config(),
):
    settings.configure(config_file)
    pp(settings.instance().model_dump())
    if beeai := settings.instance().beeai:
        env_update = {}
        model = None
        if beeai.openai is not None:
            env_update["OPENAI_API_KEY"] = beeai.openai.api_key
            if beeai.openai.org is not None:
                env_update["OPENAI_ORGANIZATION"] = beeai.openai.org
            if beeai.openai.model is not None:
                env_update["OPENAI_CHAT_MODEL"] = beeai.openai.model
            model = "openai"
        elif beeai.anthropic is not None:
            model = "anthropic"
            env_update["ANTHROPIC_API_KEY"] = beeai.anthropic.api_key
            if beeai.anthropic.chat_model is not None:
                env_update["ANTHROPIC_CHAT_MODEL"] = beeai.anthropic.chat_model
        elif beeai.ollama is not None:
            model = f"ollama:{beeai.ollama.model}"
        environ.update(env_update)

    if model is None:
        raise ValueError(f"No chat model specified in {config_file}.")

    logger().info(f"Starting {model} chat model with {settings.instance()}")
    history_file = Path("~/.mcp.history").expanduser()
    if not history_file.exists():
        history_file.touch()
    try:
        readline.read_history_file("")
    except (FileNotFoundError, PermissionError) as _:
        pass

    try:
        asyncio.run(do_chat(model, Prompt()))
    finally:
        try:
            readline.write_history_file("/tmp/history")
        except (FileNotFoundError, PermissionError) as _:
            pass


if __name__ == "__main__":
    import logging

    logging.basicConfig(level=logging.DEBUG, filename=None)
    app()
