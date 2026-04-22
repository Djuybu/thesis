# Contributing to Dremio MCP Server

Thank you for your interest in contributing to the Dremio MCP Server project! This document provides guidelines and information for contributors.

## Table of Contents

-   [Code of Conduct](#code-of-conduct)
-   [Getting Started](#getting-started)
    -   [Development Setup](#development-setup)
    -   [Project Structure](#project-structure)
-   [Making Contributions](#making-contributions)
    -   [Pull Request Process](#pull-request-process)
    -   [Development Guidelines](#development-guidelines)
-   [Documentation](#documentation)
-   [Community](#community)

## Code of Conduct

This project follows the Contributor Covenant Code of Conduct. Please read [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md) before contributing. By participating, you are expected to uphold this code.

## Getting Started

### Development Setup

1. Install Python 3.11 or later
2. Install the `uv` package manager:

    ```bash
    # Follow instructions at https://docs.astral.sh/uv/guides/install-python/
    ```

3. Fork the repository:

4. Install dependencies:

    ```bash
    cd dremio-mcp-server
    uv sync
    ```

5. Verify installation:
    ```bash
    uv run dremio-mcp-server --help
    ```

### Project Structure

```
dremio-mcp-server/
├── src/
│   └── dremioai/
│       ├── config/      # Configuration management
│       ├── servers/     # MCP server implementation
│       └── tools/       # Dremio tools implementation
└── docs/                # Documentation
```

## Making Contributions

### Pull Request Process

1. Fork the repository and create your branch from `main`:

    ```bash
    git checkout -b feature/your-feature-name
    ```

2. Make your changes, ensuring you:

    - Follow the coding style guidelines
    - Add/update tests as needed
    - Update documentation as needed
    - Keep commits atomic and well-described

3. Push to your fork and submit a pull request

4. Wait for review. The maintainers will review your PR and might request changes

### Development Guidelines

1. **Code Style**

    - Follow PEP 8 guidelines
    - Use type hints for function arguments and return values
    - Document functions and classes using docstrings

2. **Commit Messages**

    - Use clear, descriptive commit messages
    - Start with a verb in imperative mood (e.g., "Add", "Fix", "Update")
    - Reference issues if applicable

3. **Feature Development**
    - Create an issue before starting significant work
    - Discuss major changes in issues before implementing
    - Keep changes focused and scoped

## Documentation

-   Update documentation for new features or changes
-   Include docstrings for new functions and classes
-   Update README.md if adding new features
-   Add examples for significant new functionality
-   Documentation should be in Markdown format

## Community

-   Report bugs and request features through GitHub issues
-   Join discussions in the issues and pull requests
-   Help other contributors and users
-   Share improvements and success stories

## Tool Development

When adding new tools:

1. Inherit from the `Tools` class:

    ```python
    from dremioai.tools.tools import Tools
    from dremioai.config.tools import ToolType

    class MyNewTool(Tools):
        For: ClassVar[Annotated[ToolType, ToolType.FOR_SELF]]

        async def invoke(self) -> Dict[str, Any]:
            """Tool description here"""
            # Implementation
            pass
    ```

2. Add appropriate tests
3. Update documentation

## Questions or Need Help?

Feel free to:

-   Open an issue for questions
-   Ask for clarification on existing issues
-   Reach out to maintainers

Thank you for contributing to Dremio MCP Server!
