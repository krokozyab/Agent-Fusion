#!/bin/bash
# ==============================================================================
# Agent Fusion Startup Script - macOS and Linux
# ==============================================================================
# This script starts the Agent Fusion orchestrator server.
# Usage:
#   ./start.sh                    # Use default fusionagent.toml
#   ./start.sh -c fusionagent_win.toml   # Use custom config file
# ==============================================================================

set -e  # Exit on error

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Default configuration
CONFIG_FILE="fusionagent.toml"
JAR_FILE="build/libs/orchestrator-0.1.0-all.jar"

# Parse command line arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        -c|--config)
            CONFIG_FILE="$2"
            shift 2
            ;;
        -j|--jar)
            JAR_FILE="$2"
            shift 2
            ;;
        -h|--help)
            echo "Agent Fusion Startup Script"
            echo ""
            echo "Usage: ./start.sh [OPTIONS]"
            echo ""
            echo "Options:"
            echo "  -c, --config FILE    Use custom config file (default: fusionagent.toml)"
            echo "  -j, --jar FILE       Use custom JAR file (default: build/libs/orchestrator-0.1.0-all.jar)"
            echo "  -h, --help           Show this help message"
            echo ""
            echo "Examples:"
            echo "  ./start.sh"
            echo "  ./start.sh --config fusionagent_win.toml"
            echo "  ./start.sh --jar /path/to/custom.jar"
            exit 0
            ;;
        *)
            echo "Unknown option: $1"
            echo "Use --help for usage information"
            exit 1
            ;;
    esac
done

echo -e "${BLUE}╔════════════════════════════════════════════════════════════════╗${NC}"
echo -e "${BLUE}║          Agent Fusion - Orchestrator Server                  ║${NC}"
echo -e "${BLUE}╚════════════════════════════════════════════════════════════════╝${NC}"
echo ""

# Check if Java is installed
echo -ne "${YELLOW}Checking Java installation...${NC}"
if ! command -v java &> /dev/null; then
    echo -e " ${RED}✗ FAILED${NC}"
    echo -e "${RED}Error: Java is not installed or not in PATH${NC}"
    echo "Please install Java 21 or higher and try again."
    exit 1
fi
echo -e " ${GREEN}✓ OK${NC}"

# Check Java version
JAVA_VERSION=$(java -version 2>&1 | grep -oP 'version "\K[^"]+' || echo "unknown")
echo "  Java version: $JAVA_VERSION"
echo ""

# Check if config file exists
echo -ne "${YELLOW}Checking configuration file...${NC}"
if [ ! -f "$CONFIG_FILE" ]; then
    echo -e " ${RED}✗ FAILED${NC}"
    echo -e "${RED}Error: Configuration file not found: $CONFIG_FILE${NC}"
    echo ""
    echo "Available config files:"
    ls -la fusionagent*.toml 2>/dev/null || echo "  (none found)"
    exit 1
fi
echo -e " ${GREEN}✓ OK${NC}"
echo "  Config: $CONFIG_FILE"
echo ""

# Check if JAR file exists
echo -ne "${YELLOW}Checking JAR file...${NC}"
if [ ! -f "$JAR_FILE" ]; then
    echo -e " ${RED}✗ FAILED${NC}"
    echo -e "${RED}Error: JAR file not found: $JAR_FILE${NC}"
    echo ""
    echo "To build the JAR file, run:"
    echo "  ./gradlew shadowJar"
    exit 1
fi
echo -e " ${GREEN}✓ OK${NC}"
echo "  JAR: $JAR_FILE"
echo ""

# Calculate JAR size
JAR_SIZE=$(du -h "$JAR_FILE" | cut -f1)
echo "  Size: $JAR_SIZE"
echo ""

# Display startup information
echo -e "${GREEN}Starting Agent Fusion...${NC}"
echo ""
echo "  Config file: $CONFIG_FILE"
echo "  JAR file: $JAR_FILE"
echo ""
echo -e "${BLUE}╔════════════════════════════════════════════════════════════════╗${NC}"
echo -e "${BLUE}║              Access Points                                   ║${NC}"
echo -e "${BLUE}╠════════════════════════════════════════════════════════════════╣${NC}"
echo -e "${BLUE}║  MCP Server (Agent Connection):                              ║${NC}"
echo -e "${BLUE}║    http://127.0.0.1:3000                                     ║${NC}"
echo -e "${BLUE}║                                                              ║${NC}"
echo -e "${BLUE}║  Web Dashboard:                                              ║${NC}"
echo -e "${BLUE}║    http://localhost:8081                                     ║${NC}"
echo -e "${BLUE}║                                                              ║${NC}"
echo -e "${BLUE}║  Press Ctrl+C to stop the server                             ║${NC}"
echo -e "${BLUE}╚════════════════════════════════════════════════════════════════╝${NC}"
echo ""

# Run the JAR file
java -jar "$JAR_FILE" --config "$CONFIG_FILE"
