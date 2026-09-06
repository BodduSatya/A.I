import os
import asyncio
from dotenv import load_dotenv
from langchain_mcp_adapters.client import MultiServerMCPClient
from langchain.agents import create_agent
from langchain.chat_models import init_chat_model

load_dotenv()

async def run_agent():

    client = MultiServerMCPClient(
        {
            "bright_data": {
                "command": "npx",
                "args": ["@brightdata/mcp"],
                "env": {
                    "API_TOKEN": os.getenv("BRIGHT_DATA_API_TOKEN"),
                    "WEB_UNLOCKER_ZONE": os.getenv("WEB_UNLOCKER_ZONE", "unblocker"),
                    "BROWSER_ZONE": os.getenv("BROWSER_ZONE", "scraping_browser")
                },
                "transport": "stdio",
            },
        }
    )
    tools = await client.get_tools()
    model = init_chat_model(
        model="openai/gpt-4.1",
        model_provider="openai",
        base_url="https://openrouter.ai/api/v1",
        api_key=os.getenv("OPENROUTER_API_KEY"),
        max_tokens=2000,
    )
    agent = create_agent(model, tools, system_prompt="You are a web search agent with access to brightdata tool to get latest data")
    agent_response = await agent.ainvoke({"messages": "Tell me available flights from Bangalore to Delhi on September 06 2026"})
    print(agent_response["messages"][-1].content)

if __name__ == "__main__":
    asyncio.run(run_agent())