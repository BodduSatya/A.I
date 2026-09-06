# MCP Agent Demos

This project demonstrates single-agent and multi-agent workflows built with LangGraph and the Bright Data MCP server.

## Installation

```bash
python -m pip install -r requirements.txt
```

## Resources

- Checkout Bright Data here: https://brightdata.com/ai
- Bright Data GitHub: https://github.com/brightdata/brightdata-mcp
- LangGraph Documentation to create agent: https://docs.langchain.com/oss/python/langchain/agents
- LangChain MCP Adapters GitHub: https://github.com/langchain-ai/langchain-mcp-adapters
- LangGraph Multi-agent Supervisor Documentation: https://docs.langchain.com/oss/python/langgraph/workflows-agents

## Demos

### Single Agent Demo

```
D:\GitProjects\A.I\MCP> python single_agent_demo.py
```

Sample output:

```
Here are some platforms where you can check and book available flights from Bangalore to Delhi for September 06, 2026:
1. MakeMyTrip: Around 16 flights are available daily with fares starting from ₹7,681. [Link to MakeMyTrip](https://www.makemytrip.com/flights/bangalore-new_delhi-cheap-airtickets.html)
2. GoIbibo: Multiple flights available, lowest fares around ₹7,681–₹8,380. [Link to GoIbibo](https://www.goibibo.com/flights/bangalore-to-delhi-flights/)
3. Ixigo: Fare for 06 Sep 2026 starts at ₹8,364. [Link to Ixigo](https://www.ixigo.com/cheap-flights/bengaluru-new-delhi-blr-del)
4. Yatra: Over 155+ daily flights from various carriers such as IndiGo, SpiceJet, Air India Express, Akasa Air, with fares from ₹8,249. [Link to Yatra](https://www.yatra.com/flight-schedule/bangalore-to-delhi-flights.html)
5. Skyscanner: Compare and book flights with no added fees. [Link to Skyscanner](https://www.skyscanner.co.in/routes/blr/del/bengaluru-to-delhi-indira-gandhi-international.html)

Typical direct flight duration is about 2h 35m to 2h 45m.

For specific timings and seat availability, it's recommended to visit one of these links and enter your travel date (September 06, 2026) as options and schedules may be updated by airlines. If you need a list of airlines or detailed schedules for that specific day, let me know!
```

### Multi-Agent Demo

```
PS D:\GitProjects\A.I\MCP> python .\multi_agent_demo.py
```

Sample output:

```
Update from node supervisor:
================================= Tool Message =================================
Name: transfer_to_stock_finder_agent
Successfully transferred to stock_finder_agent

Update from node stock_finder_agent:
================================= Tool Message =================================
Name: transfer_back_to_supervisor
Successfully transferred back to supervisor

Update from node supervisor:
================================= Tool Message =================================
Name: transfer_to_market_data_agent
Successfully transferred to market_data_agent

Update from node market_data_agent:
================================= Tool Message =================================
Name: transfer_back_to_supervisor
Successfully transferred back to supervisor

Update from node supervisor:
================================= Tool Message =================================
Name: transfer_to_news_analyst_agent
Successfully transferred to news_analyst_agent

Update from node news_analyst_agent:
================================= Tool Message =================================
Name: transfer_back_to_supervisor
Successfully transferred back to supervisor

Update from node supervisor:
================================= Tool Message =================================
Name: transfer_to_price_recommender_agent
Successfully transferred to price_recommender_agent

Update from node price_recommender_agent:
================================= Tool Message =================================
Name: transfer_back_to_supervisor
Successfully transferred back to supervisor

Update from node supervisor:
================================== Ai Message ==================================
Name: supervisor
Here are two promising NSE stocks I've researched: Reliance Industries Ltd (RELIANCE) and Tata Motors Ltd (TATAMOTORS).
Now, I will fetch the latest market data for these stocks to proceed with a more informed recommendation. Let me know if you would like to focus on only one, or on both.
```
