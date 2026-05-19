# Enigma

A tool for deobfuscation of Java bytecode. Forked from <https://bitbucket.org/cuchaz/enigma>, copyright Jeff Martin.

## License

Enigma is distributed under the [LGPL-3.0](LICENSE).

Enigma includes the following open-source libraries:

- [Vineflower](https://github.com/Vineflower/vineflower) (Apache-2.0)
- A [modified version](https://github.com/FabricMC/cfr) of [CFR](https://github.com/leibnitz27/cfr) (MIT)
- A [modified version](https://github.com/FabricMC/procyon) of [Procyon](https://bitbucket.org/mstrobel/procyon) (Apache-2.0)
- [SyntaxPane](https://github.com/Sciss/SyntaxPane) (Apache-2.0)
- [FlatLaf](https://github.com/JFormDesigner/FlatLaf) (Apache-2.0)
- [jopt-simple](https://github.com/jopt-simple/jopt-simple) (MIT)
- [ASM](https://asm.ow2.io/) (BSD-3-Clause)

## Usage

Pre-compiled jars can be found on the [fabric maven](https://maven.fabricmc.net/cuchaz/enigma-swing/).

### Launching the GUI

`java -jar enigma.jar`

### On the command line

`java -cp enigma.jar cuchaz.enigma.command.Main`

## MCP Server

This fork adds a built-in HTTP [MCP](https://modelcontextprotocol.io/) server so AI
clients can read and edit the mappings of the currently loaded project.

### Starting

Use the **MCP** menu in the GUI:

- **Start / Stop MCP Server** — start or stop the server.
- **Configure** — set the listen port (default `32412`), whether to start
  automatically on launch, and whether to listen on `0.0.0.0` (all interfaces)
  instead of loopback only.

The server binds to `127.0.0.1` by default and speaks JSON-RPC 2.0 / MCP over a
single `POST /mcp` endpoint (Streamable HTTP, no extra dependencies — JDK
`HttpServer` + Gson). Edits are routed through the GUI controller, so the class
tree, decompiler view and collaboration server stay in sync.

### Tools

| Tool | Description |
| --- | --- |
| `list_packages` | List packages by prefix, in the obfuscated or deobfuscated namespace |
| `list_classes` | List classes in a package |
| `list_members` | List a class's fields, methods and parameters with their status |
| `rename` | Rename a class, field, method or parameter |
| `mark_deobfuscated` / `reset_obfuscated` | Mark as not needing deobfuscation, or revert to obfuscated |
| `get_javadoc` / `set_javadoc` | Read or set javadoc |
| `search` | Search classes, methods and fields by name |
| `decompile_class` | Decompile a class with mappings applied (selectable decompiler) |
| `save_mappings` | Save mappings to the file chosen in Enigma |

Every obfuscated/deobfuscated name and renamable entry is reported with its
status (`obfuscated` / `deobfuscated` / `proposed` / `unobfuscated`). Names may
be supplied in either the obfuscated or deobfuscated namespace.

### Log panel

While the MCP server is running an **MCP Log** tab appears in the bottom-right
docked panel (alongside the collaboration tabs). It shows a table of operations
(time / operation / class / detail); double-click a row to jump to the affected
class. Operations are also printed to the console with an `[MCP]` prefix.
