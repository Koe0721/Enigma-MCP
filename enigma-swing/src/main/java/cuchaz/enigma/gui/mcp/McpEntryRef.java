package cuchaz.enigma.gui.mcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.google.gson.JsonObject;

import cuchaz.enigma.EnigmaProject;
import cuchaz.enigma.analysis.index.EntryIndex;
import cuchaz.enigma.source.RenamableTokenType;
import cuchaz.enigma.translation.TranslateResult;
import cuchaz.enigma.translation.mapping.EntryRemapper;
import cuchaz.enigma.translation.mapping.ResolutionStrategy;
import cuchaz.enigma.translation.representation.AccessFlags;
import cuchaz.enigma.translation.representation.TypeDescriptor;
import cuchaz.enigma.translation.representation.entry.ClassEntry;
import cuchaz.enigma.translation.representation.entry.Entry;
import cuchaz.enigma.translation.representation.entry.FieldEntry;
import cuchaz.enigma.translation.representation.entry.LocalVariableEntry;
import cuchaz.enigma.translation.representation.entry.MethodEntry;
import cuchaz.enigma.translation.representation.entry.ParentedEntry;

/**
 * Helpers to resolve user-supplied names (obfuscated or deobfuscated) to Enigma entries
 * and to describe entries in a stable JSON-friendly shape.
 */
public final class McpEntryRef {
	private McpEntryRef() {
	}

	public static String normalize(String name) {
		return name == null ? null : name.replace('.', '/');
	}

	public static ClassEntry resolveClass(EnigmaProject project, String rawName) {
		if (rawName == null || rawName.isEmpty()) {
			throw new McpException("Class name is required");
		}

		String name = normalize(rawName);
		EntryIndex index = project.getJarIndex().getEntryIndex();

		ClassEntry direct = null;

		try {
			direct = new ClassEntry(name);
		} catch (IllegalArgumentException ignored) {
			// not a valid obfuscated-form name, fall through to deobfuscated lookup
		}

		if (direct != null && index.hasClass(direct)) {
			return direct;
		}

		EntryRemapper mapper = project.getMapper();

		for (ClassEntry obf : index.getClasses()) {
			if (mapper.deobfuscate(obf).getFullName().equals(name)) {
				return obf;
			}
		}

		throw new McpException("Class not found: " + rawName);
	}

	public static MethodEntry resolveMethod(EnigmaProject project, ClassEntry obfClass, String name, String desc) {
		if (name == null || name.isEmpty()) {
			throw new McpException("Method name is required");
		}

		EntryRemapper mapper = project.getMapper();
		List<MethodEntry> matches = new ArrayList<>();

		for (MethodEntry method : project.getJarIndex().getEntryIndex().getMethods()) {
			if (!method.getParent().equals(obfClass)) {
				continue;
			}

			MethodEntry deobf = mapper.deobfuscate(method);
			boolean nameMatch = method.getName().equals(name) || deobf.getName().equals(name);

			if (!nameMatch) {
				continue;
			}

			if (desc != null && !method.getDesc().toString().equals(desc) && !deobf.getDesc().toString().equals(desc)) {
				continue;
			}

			matches.add(method);
		}

		if (matches.isEmpty()) {
			throw new McpException("Method not found: " + name + (desc == null ? "" : desc));
		}

		if (matches.size() > 1) {
			throw new McpException("Ambiguous method '" + name + "', specify 'desc' to disambiguate");
		}

		return matches.get(0);
	}

	public static FieldEntry resolveField(EnigmaProject project, ClassEntry obfClass, String name, String desc) {
		if (name == null || name.isEmpty()) {
			throw new McpException("Field name is required");
		}

		EntryRemapper mapper = project.getMapper();
		List<FieldEntry> matches = new ArrayList<>();

		for (FieldEntry field : project.getJarIndex().getEntryIndex().getFields()) {
			if (!field.getParent().equals(obfClass)) {
				continue;
			}

			FieldEntry deobf = mapper.deobfuscate(field);
			boolean nameMatch = field.getName().equals(name) || deobf.getName().equals(name);

			if (!nameMatch) {
				continue;
			}

			if (desc != null && !field.getDesc().toString().equals(desc) && !deobf.getDesc().toString().equals(desc)) {
				continue;
			}

			matches.add(field);
		}

		if (matches.isEmpty()) {
			throw new McpException("Field not found: " + name);
		}

		if (matches.size() > 1) {
			throw new McpException("Ambiguous field '" + name + "', specify 'desc' to disambiguate");
		}

		return matches.get(0);
	}

	public static List<LocalVariableEntry> parametersOf(EnigmaProject project, MethodEntry method) {
		AccessFlags access = project.getJarIndex().getEntryIndex().getMethodAccess(method);
		boolean isStatic = access != null && access.isStatic();
		List<TypeDescriptor> args = method.getDesc().getArgumentDescs();
		List<LocalVariableEntry> result = new ArrayList<>();
		int slot = isStatic ? 0 : 1;

		for (TypeDescriptor arg : args) {
			result.add(new LocalVariableEntry(method, slot, "", true, null));
			slot += arg.getSize();
		}

		return result;
	}

	public static LocalVariableEntry resolveParameter(EnigmaProject project, MethodEntry method, int paramIndex) {
		List<LocalVariableEntry> params = parametersOf(project, method);

		if (paramIndex < 0 || paramIndex >= params.size()) {
			throw new McpException("Parameter index out of range: " + paramIndex + " (method has " + params.size() + " parameters)");
		}

		return params.get(paramIndex);
	}

	public static String status(EntryRemapper mapper, Entry<?> obf) {
		RenamableTokenType type = mapper.extendedDeobfuscate(obf).getType();
		return (type == null ? RenamableTokenType.OBFUSCATED : type).name().toLowerCase(Locale.ROOT);
	}

	private static String arg(JsonObject args, String key) {
		return args != null && args.has(key) && !args.get(key).isJsonNull() ? args.get(key).getAsString() : null;
	}

	/**
	 * Resolves the entry targeted by an MCP edit tool's arguments, following overrides
	 * to the root declaration so the result matches where Enigma stores the mapping.
	 *
	 * @param project the loaded project
	 * @param args the tool arguments (kind, class, name, desc, paramIndex)
	 * @return the resolved obfuscated entry
	 */
	public static Entry<?> resolveTarget(EnigmaProject project, JsonObject args) {
		String kind = arg(args, "kind");
		String className = arg(args, "class");

		if (kind == null || className == null) {
			throw new McpException("Missing required argument: kind/class");
		}

		ClassEntry classEntry = resolveClass(project, className);
		Entry<?> entry;

		switch (kind.toLowerCase(Locale.ROOT)) {
		case "class":
			entry = classEntry;
			break;
		case "field":
			entry = resolveField(project, classEntry, arg(args, "name"), arg(args, "desc"));
			break;
		case "method":
			entry = resolveMethod(project, classEntry, arg(args, "name"), arg(args, "desc"));
			break;
		case "parameter": {
			MethodEntry method = resolveMethod(project, classEntry, arg(args, "name"), arg(args, "desc"));
			entry = resolveParameter(project, method, args.get("paramIndex").getAsInt());
			break;
		}
		default:
			throw new McpException("Unknown kind: " + kind + " (expected class, field, method or parameter)");
		}

		java.util.Collection<Entry<?>> resolved = project.getMapper().getObfResolver().resolveEntry(entry, ResolutionStrategy.RESOLVE_ROOT);
		return resolved.isEmpty() ? entry : resolved.iterator().next();
	}

	/**
	 * Like {@link #resolveTarget} but returns {@code null} instead of throwing,
	 * for best-effort navigation in the log panel.
	 *
	 * @param project the loaded project, may be {@code null}
	 * @param args the tool arguments
	 * @return the resolved entry, or {@code null} if it cannot be resolved
	 */
	public static Entry<?> resolveTargetSafe(EnigmaProject project, JsonObject args) {
		if (project == null) {
			return null;
		}

		try {
			return resolveTarget(project, args);
		} catch (RuntimeException e) {
			return null;
		}
	}

	public static Map<String, Object> describe(EnigmaProject project, Entry<?> obf) {
		EntryRemapper mapper = project.getMapper();
		Map<String, Object> map = new LinkedHashMap<>();
		TranslateResult<? extends Entry<?>> result = mapper.extendedDeobfuscate(obf);
		Entry<?> deobf = result.getValue();

		if (obf instanceof ClassEntry classEntry) {
			map.put("kind", "class");
			map.put("obfuscated", classEntry.getFullName());
			map.put("deobfuscated", ((ClassEntry) deobf).getFullName());
		} else if (obf instanceof MethodEntry methodEntry) {
			map.put("kind", "method");
			map.put("class", methodEntry.getParent().getFullName());
			map.put("obfuscated", methodEntry.getName());
			map.put("deobfuscated", deobf.getName());
			map.put("descriptor", methodEntry.getDesc().toString());
			map.put("constructor", methodEntry.isConstructor());
		} else if (obf instanceof FieldEntry fieldEntry) {
			map.put("kind", "field");
			map.put("class", fieldEntry.getParent().getFullName());
			map.put("obfuscated", fieldEntry.getName());
			map.put("deobfuscated", deobf.getName());
			map.put("descriptor", fieldEntry.getDesc().toString());
		} else if (obf instanceof LocalVariableEntry localVariableEntry) {
			String mapped = deobf.getName();
			map.put("kind", "parameter");
			map.put("method", localVariableEntry.getParent().getName());
			map.put("index", localVariableEntry.getIndex());
			map.put("deobfuscated", mapped == null || mapped.isEmpty() ? null : mapped);
		}

		String javadoc = deobf instanceof ParentedEntry<?> parented ? parented.getJavadocs() : mapper.getDeobfMapping(obf).javadoc();
		map.put("javadoc", javadoc);
		map.put("status", status(mapper, obf));
		map.put("renamable", project.isRenamable(obf));
		return map;
	}
}
