# Agent Fusion

Agent Fusion lets multiple AI coding assistants work together on your project while sharing a **shared knowledge base**. Instead of each AI starting from scratch, they all work from the same up-to-date understanding of your code.

It has two core parts:

- **Shared Brain** – Automatically indexes your project files, remembers them as searchable knowledge, and keeps everything in sync. All AI assistants can instantly search and understand your entire codebase.
- **Task Manager** – Coordinates work between multiple AIs, lets them vote on important decisions (consensus), and shows you everything happening in a web dashboard.

🎥 **[Watch the demo](https://youtu.be/kXkTh0fJ0Lc)** to see AI assistants collaborating in action.

---

## Getting Started

| Start Here | What You'll Learn |
|----------|---------|
| **[Installation Guide](docs/INSTALL.md)** | Step-by-step setup (takes 5-10 minutes) |
| **[How It Works](docs/CONTEXT_AND_AGENTS.md)** | Simple explanation of Shared Brain and how AI assistants use it |
| **[AI Assistant Instructions](docs/AGENT_ORCHESTRATOR_INSTRUCTIONS.md)** | What to tell your AI assistants (Claude, Codex, etc.) when they connect |

## Technical Documentation

For developers and advanced users:

| Reference | Details |
|----------|---------|
| [Task Manager Guide](docs/README_TASK_ORCHESTRATOR.md) | How task routing, voting, and coordination works |
| [Shared Brain Guide](docs/README_CONTEXT_ADDON.md) | How file indexing, search, and sync works |
| [API Reference](docs/API_REFERENCE.md) | All available endpoints and tools |
| [Development Guide](docs/DEVELOPMENT.md) | Building, testing, contributing |

---

## The Shared Brain

The **Shared Brain** is the heart of Agent Fusion. It automatically:

1. **Watches your project** – Automatically finds and tracks all your code files
2. **Understands the code** – Creates searchable knowledge (using AI embeddings) so the meaning of code is captured, not just the text
3. **Keeps everything in sync** – When you change files, the Shared Brain updates instantly
4. **Answers questions** – All your AI assistants can ask "What is this function?" or "Find code similar to X" and get instant answers

The Shared Brain is configured in `fusionagent.toml` and stores everything in a local database file that never leaves your computer.

**In practice**: Instead of pasting code into every AI conversation, your assistants can search the Shared Brain once—it's always there and always current.

## How AI Assistants Work Together

Your AI assistants (Claude, Codex, etc.) work together using the **Task Manager**:

1. **One AI starts a task** – "Design a new authentication system"
2. **The system routes it smartly** – Simple tasks go to one AI, complex/important tasks go to multiple AIs to discuss
3. **AI assistants collaborate** – They can see each other's ideas, discuss pros/cons
4. **The group decides** – For important decisions, they vote and the system shows you all viewpoints
5. **Everything is tracked** – All proposals, votes, and final decisions are saved so you can see the full reasoning

You can see everything happening in real-time on the web dashboard. All AIs are working from the same Shared Brain, so they stay coordinated.

## What's Inside

### Shared Brain
The engine that keeps your code indexed and searchable:
- Automatically finds all project files
- Creates AI-powered search index (everything searchable by meaning, not just keywords)
- Stays in sync as you edit files
- Provides fast answers to "find code similar to X" or "what does this do?"

### Task Manager
The coordination system for multiple AIs:
- Routes tasks to the right AI(s) based on complexity
- Enables voting and consensus for important decisions
- Shows all activity on a web dashboard
- Saves complete history of decisions and reasoning

---

## In-Depth Guides

For developers and technical setup:

| Question | Where to Look |
|----------|---------------|
| How do I install it? | [Installation Guide](docs/INSTALL.md) |
| How does the Shared Brain work? | [Shared Brain Architecture](docs/CONTEXT_ADDON_ARCHITECTURE.md) |
| How does the Task Manager work? | [Task Manager Guide](docs/README_TASK_ORCHESTRATOR.md) |
| How do I use it with my AI assistants? | [AI Assistant Instructions](docs/AGENT_ORCHESTRATOR_INSTRUCTIONS.md) |
| What API endpoints are available? | [API Reference](docs/API_REFERENCE.md) |
| How do I deploy this? | [Deployment Guide](docs/DEPLOYMENT_NOTES.md) |

---

## Key Features

✅ **Multiple AI Assistants, Same Brain** – Connect Claude, Codex, Gemini, Amazon Q—they all see the same code knowledge

✅ **Automatic Routing** – Simple tasks go to one AI, important/complex tasks automatically go to multiple AIs for discussion

✅ **Real-Time Collaboration** – Watch AIs collaborate and vote on decisions in real-time on your web dashboard

✅ **Complete Transparency** – Every decision, proposal, and vote is saved with full reasoning—no black boxes

✅ **Private & Local** – Everything runs on your machine. Your code never leaves your computer

✅ **Always Fresh** – Automatically detects file changes and updates the Shared Brain instantly

## How It Routes Work

The system smartly decides how to handle each task:

- **Quick tasks** (fixing a typo, writing a docstring) → Go to one AI
- **Complex tasks** (design new feature) → Go to two AIs who discuss and decide together
- **Critical decisions** (security, architecture) → All AIs vote, you see all viewpoints
- **Can be parallelized** (test writing, code generation) → Multiple AIs work in parallel on pieces

## License

MIT
