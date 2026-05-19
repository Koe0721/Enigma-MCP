package cuchaz.enigma.gui.mcp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import cuchaz.enigma.EnigmaProject;
import cuchaz.enigma.gui.Gui;
import cuchaz.enigma.translation.representation.entry.Entry;

/**
 * Minimal JSON-RPC 2.0 / MCP dispatcher. Implements the subset of the Model Context
 * Protocol needed by HTTP clients: initialize, tools/list, tools/call and ping.
 */
public final class McpProtocol {
	private static final String SERVER_NAME = "enigma-mcp";
	private static final String DEFAULT_PROTOCOL = "2024-11-05";
	private static final String INSTRUCTIONS = """
			This server edits the mappings of a Java deobfuscation project loaded in Enigma.

			Renaming rules — rename / mark_deobfuscated / reset_obfuscated only work on \
			renamable entries. Before calling them, check list_members: act ONLY on entries \
			whose "renamable" is true. Never target:
			- constructors / static initializers (obfuscated name "<init>" or "<clinit>", or \
			"constructor": true);
			- JDK Object overrides (equals, hashCode, toString, clone, finalize, getClass, \
			notify, notifyAll, wait) and synthetic enum methods (values, valueOf);
			- entries not present in the loaded jar.
			Entries whose "status" is already "deobfuscated" or "unobfuscated" usually need no \
			further action.

			Names may be given in either the obfuscated or the deobfuscated namespace. For \
			overloaded methods/fields pass "desc" to disambiguate. Prefer reading list_members \
			or decompile_class for context before renaming.""";

	private final Gson gson = new GsonBuilder().serializeNulls().create();
	private final McpTools tools;
	private final Gui gui;

	public McpProtocol(Gui gui) {
		this.gui = gui;
		this.tools = new McpTools(gui);
	}

	/**
	 * Handles a raw request body.
	 *
	 * @param body the JSON-RPC request text
	 * @return the JSON response text, or {@code null} if the request was a notification
	 */
	public String handle(String body) {
		JsonElement root;

		try {
			root = JsonParser.parseString(body);
		} catch (RuntimeException e) {
			return this.gson.toJson(errorResponse(null, -32700, "Parse error: " + e.getMessage()));
		}

		if (root.isJsonArray()) {
			JsonArray responses = new JsonArray();

			for (JsonElement element : root.getAsJsonArray()) {
				JsonObject response = handleSingle(element.getAsJsonObject());

				if (response != null) {
					responses.add(response);
				}
			}

			return responses.isEmpty() ? null : this.gson.toJson(responses);
		}

		JsonObject response = handleSingle(root.getAsJsonObject());
		return response == null ? null : this.gson.toJson(response);
	}

	private JsonObject handleSingle(JsonObject request) {
		JsonElement id = request.get("id");
		String method = request.has("method") ? request.get("method").getAsString() : "";
		JsonObject params = request.has("params") && request.get("params").isJsonObject() ? request.getAsJsonObject("params") : new JsonObject();

		// notifications carry no id and expect no response
		if (id == null || id.isJsonNull()) {
			return null;
		}

		try {
			switch (method) {
			case "initialize":
				return resultResponse(id, initialize(params));
			case "ping":
				return resultResponse(id, new JsonObject());
			case "tools/list":
				return resultResponse(id, listTools());
			case "tools/call":
				return resultResponse(id, callTool(params));
			default:
				return errorResponse(id, -32601, "Method not found: " + method);
			}
		} catch (McpException e) {
			return errorResponse(id, -32000, e.getMessage());
		} catch (RuntimeException e) {
			return errorResponse(id, -32603, "Internal error: " + e);
		}
	}

	private JsonObject initialize(JsonObject params) {
		String protocol = params.has("protocolVersion") ? params.get("protocolVersion").getAsString() : DEFAULT_PROTOCOL;

		JsonObject capabilities = new JsonObject();
		capabilities.add("tools", new JsonObject());

		JsonObject serverInfo = new JsonObject();
		serverInfo.addProperty("name", SERVER_NAME);
		serverInfo.addProperty("version", "4.0.2");

		JsonObject result = new JsonObject();
		result.addProperty("protocolVersion", protocol);
		result.add("capabilities", capabilities);
		result.add("serverInfo", serverInfo);
		result.addProperty("instructions", INSTRUCTIONS);
		return result;
	}

	private JsonObject callTool(JsonObject params) {
		String name = params.has("name") ? params.get("name").getAsString() : "";
		JsonObject arguments = params.has("arguments") && params.get("arguments").isJsonObject() ? params.getAsJsonObject("arguments") : new JsonObject();
		String classArg = arguments.has("class") && !arguments.get("class").isJsonNull() ? arguments.get("class").getAsString() : null;
		EnigmaProject project = this.gui == null ? null : this.gui.getController().getProject();
		Entry<?> navTarget = McpEntryRef.resolveTargetSafe(project, arguments);
		String obfClass = navTarget != null ? navTarget.getContainingClass().getFullName() : resolveObfClass(classArg);
		String oldName = currentName(project, navTarget);

		try {
			Object toolResult = dispatch(name, arguments);
			log(name, obfClass, navTarget, buildDetail(name, arguments, toolResult, project, navTarget, oldName), false);
			return textContent(this.gson.toJson(toolResult), false);
		} catch (McpException e) {
			log(name, obfClass, navTarget, e.getMessage(), true);
			throw e;
		} catch (RuntimeException e) {
			log(name, obfClass, navTarget, String.valueOf(e), true);
			throw e;
		}
	}

	private String currentName(EnigmaProject project, Entry<?> navTarget) {
		if (project == null || navTarget == null) {
			return null;
		}

		try {
			Entry<?> deobf = project.getMapper().deobfuscate(navTarget);
			return deobf instanceof cuchaz.enigma.translation.representation.entry.ClassEntry classEntry ? classEntry.getFullName() : deobf.getName();
		} catch (RuntimeException e) {
			return null;
		}
	}

	private String buildDetail(String tool, JsonObject arguments, Object result, EnigmaProject project, Entry<?> navTarget, String oldName) {
		switch (tool) {
		case "rename": {
			String newName = arguments.has("newName") && !arguments.get("newName").isJsonNull() ? arguments.get("newName").getAsString() : "?";
			return (oldName == null ? "?" : oldName) + " -> " + newName;
		}
		case "mark_deobfuscated": {
			String newName = "?";

			if (project != null && navTarget != null) {
				String target = project.getMapper().getDeobfMapping(navTarget).targetName();
				newName = target != null ? target : "?";
			}

			return (oldName == null ? "?" : oldName) + " -> " + newName;
		}
		case "reset_obfuscated":
			return (oldName == null ? "?" : oldName) + " -> (obfuscated)";
		case "set_javadoc": {
			String text = arguments.has("javadoc") && !arguments.get("javadoc").isJsonNull() ? arguments.get("javadoc").getAsString() : "";
			return text.isEmpty() ? "javadoc cleared" : "javadoc set";
		}
		case "save_mappings":
			return "saved";
		default:
			if (result instanceof java.util.Map<?, ?> map && map.containsKey("count")) {
				return "count=" + map.get("count");
			}

			return "ok";
		}
	}

	private Object dispatch(String name, JsonObject arguments) {
		Object toolResult;

		switch (name) {
		case "list_packages":
			toolResult = this.tools.listPackages(arguments);
			break;
		case "list_classes":
			toolResult = this.tools.listClasses(arguments);
			break;
		case "list_members":
			toolResult = this.tools.listMembers(arguments);
			break;
		case "rename":
			toolResult = this.tools.rename(arguments);
			break;
		case "mark_deobfuscated":
			toolResult = this.tools.markDeobfuscated(arguments);
			break;
		case "reset_obfuscated":
			toolResult = this.tools.resetObfuscated(arguments);
			break;
		case "get_javadoc":
			toolResult = this.tools.getJavadoc(arguments);
			break;
		case "set_javadoc":
			toolResult = this.tools.setJavadoc(arguments);
			break;
		case "search":
			toolResult = this.tools.search(arguments);
			break;
		case "decompile_class":
			toolResult = this.tools.decompileClass(arguments);
			break;
		case "save_mappings":
			toolResult = this.tools.saveMappings(arguments);
			break;
		default:
			throw new McpException("Unknown tool: " + name);
		}

		return toolResult;
	}

	private String resolveObfClass(String classArg) {
		if (classArg == null || this.gui == null || this.gui.getController().getProject() == null) {
			return classArg;
		}

		try {
			return McpEntryRef.resolveClass(this.gui.getController().getProject(), classArg).getFullName();
		} catch (RuntimeException e) {
			return classArg;
		}
	}

	private void log(String operation, String obfClass, Entry<?> navTarget, String detail, boolean error) {
		System.out.println("[MCP] " + operation + (obfClass == null ? "" : " " + obfClass) + (error ? " ERROR: " : " -> ") + detail);

		if (this.gui != null) {
			this.gui.getMcpLogPanel().log(operation, obfClass, navTarget, (error ? "ERROR: " : "") + detail);
		}
	}

	private JsonObject textContent(String text, boolean isError) {
		JsonObject content = new JsonObject();
		content.addProperty("type", "text");
		content.addProperty("text", text);

		JsonArray contents = new JsonArray();
		contents.add(content);

		JsonObject result = new JsonObject();
		result.add("content", contents);

		if (isError) {
			result.addProperty("isError", true);
		}

		return result;
	}

	private JsonObject listTools() {
		JsonArray toolList = new JsonArray();

		JsonObject listPackages = new JsonObject();
		listPackages.add("prefix", simple("string", "Optional package name prefix"));
		listPackages.add("domain", domainProp());
		toolList.add(rawTool("list_packages", "List packages, optionally filtered by a name prefix. Works in the obfuscated or deobfuscated namespace.", listPackages, new String[0]));

		JsonObject listClasses = new JsonObject();
		listClasses.add("package", simple("string", "Package name (empty string for the default package)"));
		listClasses.add("domain", domainProp());
		toolList.add(rawTool("list_classes", "List top-level classes in a package, with their obfuscated/deobfuscated names and status.", listClasses, new String[]{"package"}));

		JsonObject listMembers = new JsonObject();
		listMembers.add("class", simple("string", "Class name, obfuscated or deobfuscated"));
		toolList.add(rawTool("list_members", "List all renamable fields, methods and parameters of a class, each marked deobfuscated/obfuscated/proposed/unobfuscated.", listMembers, new String[]{"class"}));

		toolList.add(targetTool("rename", "Rename a class, field, method or parameter. Only works on entries where list_members reports \"renamable\": true; never target constructors (<init>/<clinit>), JDK Object overrides or enum values/valueOf.", "newName", "The new deobfuscated name", new String[]{"kind", "class", "newName"}));
		toolList.add(targetTool("mark_deobfuscated", "Mark a class/field/method/parameter as not needing deobfuscation (assigns its proposed or original name, same as the GUI right-click action). Only works on entries where list_members reports \"renamable\": true; never target constructors or non-renamable JDK/enum methods.", null, null, new String[]{"kind", "class"}));
		toolList.add(targetTool("reset_obfuscated", "Clear the mapping of a class/field/method/parameter, reverting it to obfuscated. Only meaningful for renamable entries; never target constructors or non-renamable JDK/enum methods.", null, null, new String[]{"kind", "class"}));
		toolList.add(targetTool("get_javadoc", "Get the javadoc attached to a class/field/method/parameter.", null, null, new String[]{"kind", "class"}));
		toolList.add(targetTool("set_javadoc", "Set (or clear, when empty) the javadoc of a class/field/method/parameter.", "javadoc", "Javadoc text; empty string clears it", new String[]{"kind", "class"}));

		JsonObject search = new JsonObject();
		search.add("query", simple("string", "Case-insensitive substring to search for"));
		search.add("domain", domainProp());
		search.add("limit", simple("integer", "Maximum number of results (default 50)"));
		toolList.add(rawTool("search", "Search classes, methods and fields by name substring.", search, new String[]{"query"}));

		JsonObject decompile = new JsonObject();
		decompile.add("class", simple("string", "Class name, obfuscated or deobfuscated"));
		decompile.add("decompiler", enumProp("Decompiler to use (default VINEFLOWER)", "VINEFLOWER", "CFR", "PROCYON", "BYTECODE"));
		toolList.add(rawTool("decompile_class", "Decompile a class with the current mappings applied and return its Java source.", decompile, new String[]{"class"}));

		toolList.add(rawTool("save_mappings", "Save the current mappings to the file selected in Enigma.", new JsonObject(), new String[0]));

		JsonObject result = new JsonObject();
		result.add("tools", toolList);
		return result;
	}

	private JsonObject targetTool(String name, String description, String extraKey, String extraDescription, String[] required) {
		JsonObject props = new JsonObject();
		props.add("kind", enumProp("Entry kind to target", "class", "field", "method", "parameter"));
		props.add("class", simple("string", "Class name, obfuscated or deobfuscated"));
		props.add("name", simple("string", "Member name (for field/method/parameter)"));
		props.add("desc", simple("string", "Member descriptor, optional disambiguator"));
		props.add("paramIndex", simple("integer", "0-based parameter index (for kind=parameter)"));

		if (extraKey != null) {
			props.add(extraKey, simple("string", extraDescription));
		}

		return rawTool(name, description, props, required);
	}

	private JsonObject domainProp() {
		return enumProp("Namespace to operate in (default deobf)", "obf", "deobf");
	}

	private JsonObject simple(String type, String description) {
		JsonObject schema = new JsonObject();
		schema.addProperty("type", type);
		schema.addProperty("description", description);
		return schema;
	}

	private JsonObject enumProp(String description, String... values) {
		JsonObject schema = new JsonObject();
		schema.addProperty("type", "string");
		schema.addProperty("description", description);
		JsonArray enumValues = new JsonArray();

		for (String value : values) {
			enumValues.add(value);
		}

		schema.add("enum", enumValues);
		return schema;
	}

	private JsonObject rawTool(String name, String description, JsonObject properties, String[] required) {
		JsonObject schema = new JsonObject();
		schema.addProperty("type", "object");
		schema.add("properties", properties);

		JsonArray requiredArray = new JsonArray();

		for (String key : required) {
			requiredArray.add(key);
		}

		schema.add("required", requiredArray);

		JsonObject toolObject = new JsonObject();
		toolObject.addProperty("name", name);
		toolObject.addProperty("description", description);
		toolObject.add("inputSchema", schema);
		return toolObject;
	}

	private JsonObject resultResponse(JsonElement id, JsonElement result) {
		JsonObject response = new JsonObject();
		response.addProperty("jsonrpc", "2.0");
		response.add("id", id);
		response.add("result", result);
		return response;
	}

	private JsonObject errorResponse(JsonElement id, int code, String message) {
		JsonObject error = new JsonObject();
		error.addProperty("code", code);
		error.addProperty("message", message);

		JsonObject response = new JsonObject();
		response.addProperty("jsonrpc", "2.0");
		response.add("id", id == null ? com.google.gson.JsonNull.INSTANCE : id);
		response.add("error", error);
		return response;
	}
}
