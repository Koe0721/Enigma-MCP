package cuchaz.enigma.gui.mcp;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

import javax.swing.SwingUtilities;

import com.google.gson.JsonObject;

import cuchaz.enigma.EnigmaProject;
import cuchaz.enigma.classprovider.DecompilerInputTransformingClassProvider;
import cuchaz.enigma.classprovider.ObfuscationFixClassProvider;
import cuchaz.enigma.gui.Gui;
import cuchaz.enigma.gui.config.Decompiler;
import cuchaz.enigma.source.DecompiledClassSource;
import cuchaz.enigma.source.Source;
import cuchaz.enigma.source.SourceSettings;
import cuchaz.enigma.translation.mapping.EntryChange;
import cuchaz.enigma.translation.mapping.EntryRemapper;
import cuchaz.enigma.translation.representation.entry.ClassEntry;
import cuchaz.enigma.translation.representation.entry.Entry;
import cuchaz.enigma.translation.representation.entry.FieldEntry;
import cuchaz.enigma.translation.representation.entry.LocalVariableEntry;
import cuchaz.enigma.translation.representation.entry.MethodEntry;
import cuchaz.enigma.utils.validation.ParameterizedMessage;
import cuchaz.enigma.utils.validation.PrintValidatable;
import cuchaz.enigma.utils.validation.ValidationContext;

/**
 * Implements the MCP tool surface on top of the live Enigma project. Read operations
 * use the mapper directly; write operations are routed through the GUI controller on
 * the Swing thread so the tree, decompiler and collaboration server stay in sync.
 */
public final class McpTools {
	private final Gui gui;

	public McpTools(Gui gui) {
		this.gui = gui;
	}

	private EnigmaProject requireProject() {
		EnigmaProject project = this.gui.getController().getProject();

		if (project == null) {
			throw new McpException("No JAR is open in Enigma. Open one via File > Open Jar first.");
		}

		return project;
	}

	private static String str(JsonObject args, String key) {
		return args.has(key) && !args.get(key).isJsonNull() ? args.get(key).getAsString() : null;
	}

	private static String reqStr(JsonObject args, String key) {
		String value = str(args, key);

		if (value == null || value.isEmpty()) {
			throw new McpException("Missing required argument: " + key);
		}

		return value;
	}

	private Entry<?> resolveTarget(EnigmaProject project, JsonObject args) {
		return McpEntryRef.resolveTarget(project, args);
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	private void runEdit(EnigmaProject project, Entry<?> obf, java.util.function.Function<EntryChange, EntryChange> op) {
		EntryChange change = op.apply(EntryChange.modify(obf));
		boolean nameChange = !change.getDeobfName().isUnchanged();

		if (nameChange) {
			if (obf instanceof MethodEntry method && method.isConstructor()) {
				throw new McpException("Constructors (<init>/<clinit>) cannot be renamed or marked - they have no obfuscated name.");
			}

			if (!project.isRenamable(obf)) {
				throw new McpException("Entry is not renamable: " + obf);
			}
		}

		String[] error = {null};
		Runnable runnable = () -> {
			ValidationContext vc = new ValidationContext();
			vc.setActiveElement(PrintValidatable.INSTANCE);
			this.gui.getController().applyChange(vc, change);

			if (!vc.canProceed()) {
				StringBuilder sb = new StringBuilder();

				for (ParameterizedMessage message : vc.getMessages()) {
					if (sb.length() > 0) {
						sb.append("; ");
					}

					sb.append(message.getText());
				}

				error[0] = sb.toString();
			}
		};

		try {
			if (SwingUtilities.isEventDispatchThread()) {
				runnable.run();
			} else {
				SwingUtilities.invokeAndWait(runnable);
			}
		} catch (InterruptedException | InvocationTargetException e) {
			throw new McpException("Edit failed: " + e);
		}

		if (error[0] != null) {
			throw new McpException("Edit rejected by Enigma validation: " + error[0]);
		}
	}

	public Object listPackages(JsonObject args) {
		EnigmaProject project = requireProject();
		String prefix = McpEntryRef.normalize(str(args, "prefix"));
		boolean deobf = !"obf".equalsIgnoreCase(str(args, "domain"));
		EntryRemapper mapper = project.getMapper();
		TreeSet<String> packages = new TreeSet<>();

		for (ClassEntry obf : project.getJarIndex().getEntryIndex().getClasses()) {
			String fullName = deobf ? mapper.deobfuscate(obf).getFullName() : obf.getFullName();
			String pkg = ClassEntry.getParentPackage(fullName);

			if (pkg != null && (prefix == null || pkg.startsWith(prefix))) {
				packages.add(pkg);
			}
		}

		Map<String, Object> result = new LinkedHashMap<>();
		result.put("domain", deobf ? "deobf" : "obf");
		result.put("packages", new ArrayList<>(packages));
		return result;
	}

	public Object listClasses(JsonObject args) {
		EnigmaProject project = requireProject();
		String pkg = McpEntryRef.normalize(reqStr(args, "package"));
		boolean deobf = !"obf".equalsIgnoreCase(str(args, "domain"));
		EntryRemapper mapper = project.getMapper();
		List<Object> classes = new ArrayList<>();

		for (ClassEntry obf : project.getJarIndex().getEntryIndex().getClasses()) {
			if (obf.isInnerClass()) {
				continue;
			}

			String fullName = deobf ? mapper.deobfuscate(obf).getFullName() : obf.getFullName();
			String classPkg = ClassEntry.getParentPackage(fullName);
			boolean match = pkg.isEmpty() ? classPkg == null : pkg.equals(classPkg);

			if (match) {
				classes.add(McpEntryRef.describe(project, obf));
			}
		}

		Map<String, Object> result = new LinkedHashMap<>();
		result.put("domain", deobf ? "deobf" : "obf");
		result.put("package", pkg);
		result.put("classes", classes);
		return result;
	}

	public Object listMembers(JsonObject args) {
		EnigmaProject project = requireProject();
		ClassEntry obfClass = McpEntryRef.resolveClass(project, reqStr(args, "class"));
		EntryRemapper mapper = project.getMapper();

		List<Object> fields = new ArrayList<>();

		for (FieldEntry field : project.getJarIndex().getEntryIndex().getFields()) {
			if (field.getParent().equals(obfClass)) {
				fields.add(McpEntryRef.describe(project, field));
			}
		}

		List<Object> methods = new ArrayList<>();

		for (MethodEntry method : project.getJarIndex().getEntryIndex().getMethods()) {
			if (!method.getParent().equals(obfClass)) {
				continue;
			}

			Map<String, Object> methodInfo = McpEntryRef.describe(project, method);
			List<Object> params = new ArrayList<>();
			List<LocalVariableEntry> paramEntries = McpEntryRef.parametersOf(project, method);

			for (int i = 0; i < paramEntries.size(); i++) {
				Map<String, Object> paramInfo = McpEntryRef.describe(project, paramEntries.get(i));
				paramInfo.put("paramIndex", i);
				params.add(paramInfo);
			}

			methodInfo.put("parameters", params);
			methods.add(methodInfo);
		}

		Map<String, Object> result = new LinkedHashMap<>();
		result.put("class", McpEntryRef.describe(project, obfClass));
		result.put("fields", fields);
		result.put("methods", methods);
		return result;
	}

	public Object rename(JsonObject args) {
		EnigmaProject project = requireProject();
		Entry<?> target = resolveTarget(project, args);
		String newName = reqStr(args, "newName");
		runEdit(project, target, change -> change.withDeobfName(newName));
		return McpEntryRef.describe(project, target);
	}

	public Object markDeobfuscated(JsonObject args) {
		EnigmaProject project = requireProject();
		Entry<?> target = resolveTarget(project, args);
		runEdit(project, target, change -> change.withDefaultDeobfName(project));
		return McpEntryRef.describe(project, target);
	}

	public Object resetObfuscated(JsonObject args) {
		EnigmaProject project = requireProject();
		Entry<?> target = resolveTarget(project, args);
		runEdit(project, target, EntryChange::clearDeobfName);
		return McpEntryRef.describe(project, target);
	}

	public Object getJavadoc(JsonObject args) {
		EnigmaProject project = requireProject();
		Entry<?> target = resolveTarget(project, args);
		Map<String, Object> described = McpEntryRef.describe(project, target);
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("target", described);
		result.put("javadoc", described.get("javadoc"));
		return result;
	}

	public Object setJavadoc(JsonObject args) {
		EnigmaProject project = requireProject();
		Entry<?> target = resolveTarget(project, args);
		String javadoc = str(args, "javadoc");

		if (javadoc == null || javadoc.isEmpty()) {
			runEdit(project, target, EntryChange::clearJavadoc);
		} else {
			runEdit(project, target, change -> change.withJavadoc(javadoc));
		}

		return McpEntryRef.describe(project, target);
	}

	public Object search(JsonObject args) {
		EnigmaProject project = requireProject();
		String query = reqStr(args, "query").toLowerCase(Locale.ROOT);
		boolean deobf = !"obf".equalsIgnoreCase(str(args, "domain"));
		int limit = args.has("limit") && !args.get("limit").isJsonNull() ? args.get("limit").getAsInt() : 50;
		EntryRemapper mapper = project.getMapper();
		List<Object> results = new ArrayList<>();

		for (ClassEntry obf : project.getJarIndex().getEntryIndex().getClasses()) {
			String name = deobf ? mapper.deobfuscate(obf).getFullName() : obf.getFullName();

			if (name.toLowerCase(Locale.ROOT).contains(query)) {
				results.add(McpEntryRef.describe(project, obf));

				if (results.size() >= limit) {
					return finishSearch(results, limit, true);
				}
			}
		}

		for (MethodEntry method : project.getJarIndex().getEntryIndex().getMethods()) {
			String name = deobf ? mapper.deobfuscate(method).getName() : method.getName();

			if (name.toLowerCase(Locale.ROOT).contains(query)) {
				results.add(McpEntryRef.describe(project, method));

				if (results.size() >= limit) {
					return finishSearch(results, limit, true);
				}
			}
		}

		for (FieldEntry field : project.getJarIndex().getEntryIndex().getFields()) {
			String name = deobf ? mapper.deobfuscate(field).getName() : field.getName();

			if (name.toLowerCase(Locale.ROOT).contains(query)) {
				results.add(McpEntryRef.describe(project, field));

				if (results.size() >= limit) {
					return finishSearch(results, limit, true);
				}
			}
		}

		return finishSearch(results, limit, false);
	}

	private static Object finishSearch(List<Object> results, int limit, boolean truncated) {
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("count", results.size());
		result.put("truncated", truncated);
		result.put("results", results);
		return result;
	}

	public Object decompileClass(JsonObject args) {
		EnigmaProject project = requireProject();
		ClassEntry obfClass = McpEntryRef.resolveClass(project, reqStr(args, "class"));
		String decompilerName = str(args, "decompiler");
		Decompiler decompiler = Decompiler.VINEFLOWER;

		if (decompilerName != null) {
			try {
				decompiler = Decompiler.valueOf(decompilerName.toUpperCase(Locale.ROOT));
			} catch (IllegalArgumentException e) {
				throw new McpException("Unknown decompiler: " + decompilerName + " (expected VINEFLOWER, CFR, PROCYON or BYTECODE)");
			}
		}

		cuchaz.enigma.source.Decompiler dc = decompiler.service.create(
				new ObfuscationFixClassProvider(
						new DecompilerInputTransformingClassProvider(project.getClassProvider(), project.getEnigma().getServices()),
						project.getJarIndex()
				),
				new SourceSettings(true, true)
		);

		Source raw = dc.getSource(obfClass.getFullName());
		DecompiledClassSource decompiled = new DecompiledClassSource(obfClass, raw.index());
		String source = decompiled.remapSource(project, project.getMapper().getDeobfuscator()).toString();

		Map<String, Object> result = new LinkedHashMap<>();
		result.put("class", McpEntryRef.describe(project, obfClass));
		result.put("decompiler", decompiler.name);
		result.put("source", source);
		return result;
	}

	public Object saveMappings(JsonObject args) {
		EnigmaProject project = requireProject();
		File selected = this.gui.mappingsFileChooser.getSelectedFile();

		if (selected == null) {
			throw new McpException("No mappings file is set. Use File > Save Mappings As in the Enigma window once, then retry.");
		}

		try {
			SwingUtilities.invokeAndWait(() -> this.gui.getController().saveMappings(selected.toPath()));
		} catch (InterruptedException | InvocationTargetException e) {
			throw new McpException("Save failed: " + e);
		}

		Map<String, Object> result = new LinkedHashMap<>();
		result.put("saved", selected.getAbsolutePath());
		return result;
	}
}
