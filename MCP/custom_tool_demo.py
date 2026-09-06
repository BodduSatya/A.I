import os
import asyncio
from dotenv import load_dotenv
from langchain.chat_models import init_chat_model
from langchain.agents import create_agent
from langchain_core.tools import tool

load_dotenv()

MOCK_PRICES = {
    "RELIANCE": 2945.50,
    "TCS": 4102.15,
    "INFY": 1876.30,
    "HDFCBANK": 1685.90,
}

@tool
def get_stock_price(ticker: str) -> str:
    """Get the current price for an NSE stock ticker (mock data for demo purposes)."""
    price = MOCK_PRICES.get(ticker.upper())
    if price is None:
        return f"No price data found for {ticker.upper()}."
    return f"{ticker.upper()} is currently trading at Rs.{price}"

@tool
def calculate_investment_return(principal: float, rate_percent: float, years: int) -> str:
    """Calculate compound investment return given principal, annual rate percent, and years."""
    amount = principal * (1 + rate_percent / 100) ** years
    return f"An investment of Rs.{principal} at {rate_percent}% annual return over {years} years grows to Rs.{amount:.2f}"

async def run_agent():
    model = init_chat_model(
        model="openai/gpt-4.1",
        model_provider="openai",
        base_url="https://openrouter.ai/api/v1",
        api_key=os.getenv("OPENROUTER_API_KEY"),
        max_tokens=2000,
    )
    agent = create_agent(
        model,
        tools=[get_stock_price, calculate_investment_return],
        system_prompt="You are a helpful financial assistant with access to custom tools for stock prices and investment calculations.",
    )
    response = await agent.ainvoke(
        {"messages": "What is the current price of INFY, and if I invest that amount today at 12% annual return for 5 years, what will it be worth?"}
    )
    print(response["messages"][-1].content)

if __name__ == "__main__":
    asyncio.run(run_agent())
