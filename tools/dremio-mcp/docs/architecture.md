# Dremio MCP Server Architecture

## High Level Overview

The Dremio MCP Server implements the [Model Context Protocol (MCP)](https://modelcontextprotocol.io/introduction) to create a standardized interface between Large Language Models (LLMs) and Dremio data platform. This architecture enables seamless integration between LLMs and Dremio's capabilities, allowing for natural language interactions with data and analytics workflows.

### What is MCP?

At a glance, the MCP based architecture looks like this: 


```mermaid
%%{init: { "themeVariables": { "fontFamily": "Inter" } } }%%
flowchart LR
    subgraph "LLMs"
        LLM["LLMs like Claude, etc.."]
    end
    subgraph "User's Server"
        Host["Host with MCP Client<br/>(Claude desktop, IDEs, Agent)"]
        S1["MCP Server A<br/>(for example..)"]
        S2["Dremio MCP Server"]
        Host <-->|"MCP Protocol"| S1
        Host <-->|"MCP Protocol"| S2
        Host <-->|"Interact with LLM. LLM calls<br/>tools from MCP server"| LLM
        S1 <--> D1[("Local<br/>Data Source")]
    end
    subgraph "Remote Dremio cluster"
        S2 <-->|"REST APIs<br/>Flight etc.."| D3[("Dremio")]
    end
```

The architecture consists of three main components:

1. **LLM Layer**:

    - Handles natural language understanding and generation
    - Makes intelligent decisions about which tools to use
    - Processes results and generates human-readable responses

2. **MCP Client/Host**:

    - Provides the user interface (Claude desktop, IDEs, or other agents)
    - Manages communication between LLMs and MCP servers
    - Handles tool discovery and execution

3. **MCP Servers**:
    - Dremio MCP Server: Provides specialized tools for Dremio interaction
    - Other MCP Servers: Can run alongside Dremio MCP server for additional capabilities
    - Each server exposes a standardized interface through the MCP protocol

## Interaction Flow

The following diagram illustrates the detailed interaction flow between components:

```mermaid
%%{init: { "themeVariables": { "fontFamily": "Inter" } } }%%
sequenceDiagram
    participant User
    box Claude desktop / LLM / Agent
    participant Client as MCP Client / Frontend
    participant LLM as Claude LLM
    end
    participant MCP as MCPServer
    participant DB as Dremio

    Note over Client,MCP: MCP server discovery and initialization
    alt Client initializes MCP
        Client->>MCP: List tools and prompt
        MCP->>Client: Provides tools and prompt
    end

    Note over User,Client: What sales oriented tables do I have<br/>And what insights can you get from it
    User->>Client: Submits natural languate question
    Client->>LLM: Forwards question

    loop Question requires relevant tools from MCP server
        LLM->>Client: Requests tool execution -  RunSQL
        Client->>MCP: Sends tool call - RunSQL, GetSchemaOfTable
        activate MCP
        Note right of MCP: call tools one or more <br/> times to iterrogate catalog
        MCP->>DB: Executes SQL query
        activate DB
        DB->>MCP: Provides results
        deactivate DB
        MCP-->>Client: Returns processed data
        deactivate MCP
        Client-->>LLM: Provides data
    end
    Note over Client,MCP: This process iterates as LLM processes<br/> data and asks for more tool invocations

    LLM->>Client: Generates response
    Client->>User: Delivers response
```

### Interaction Steps:

1. **Initialization Phase**:

    - MCP Client discovers available MCP servers
    - Each server provides its capabilities (tools) and prompts
    - System establishes connections and validates access

2. **Query Processing**:

    - User submits natural language questions
    - LLM analyzes the question and determines required tools
    - Tools are executed through the MCP protocol
    - Results are processed and returned to the user

3. **Iterative Processing**:
    - LLM may make multiple tool calls to gather complete information
    - Each tool call is handled independently
    - Results are accumulated and synthesized into final response

## Tool Discovery and Initialization

The following diagram shows how tools are discovered and initialized:

```mermaid
%%{init: { "themeVariables": { "fontFamily": "Inter" } } }%%
sequenceDiagram
    box Claude desktop / LLM / Agent
    participant LLM as Claude LLM
    participant Client as MCP Client / Frontend
    end
    participant MCP as MCPServer

    par Client to MCP
        Client --> MCP: List available prompts
        activate MCP
        MCP --> Client: Returns prompts
        deactivate MCP
    and Client to MCP
        Client --> MCP: List available tools
        activate MCP
        MCP --> Client: Returns tools, with arguments and description
        deactivate MCP
    end
```

### Tool Discovery Process:

1. **Prompt Discovery**:

    - Client requests available prompts from MCP server
    - Server returns specialized prompts for different use cases
    - Prompts are cached for future use

2. **Tool Discovery**:
    - Client requests available tools from MCP server
    - Server returns tool definitions including:
        - Tool names and descriptions
        - Required arguments and types
        - Expected return values
    - Tools are registered with the LLM for use

This architecture enables flexible and extensible integration between LLMs and Dremio, allowing for natural language interaction with data while maintaining security and control through the MCP protocol.
