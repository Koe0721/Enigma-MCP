package cuchaz.enigma.gui.elements;

import java.awt.BorderLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;

import cuchaz.enigma.gui.Gui;
import cuchaz.enigma.translation.representation.entry.ClassEntry;
import cuchaz.enigma.translation.representation.entry.Entry;
import cuchaz.enigma.utils.I18n;

/**
 * A docked panel (sibling of the collab Users/Messages tabs) that shows a table of
 * MCP operations. Double-clicking a row navigates to the affected class.
 */
public class McpLogPanel extends JPanel {
	private static final int MAX_ROWS = 300;
	private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");

	private final Gui gui;
	private final DefaultTableModel model;
	private final JTable table;
	private final JScrollPane scrollPane;
	private final List<String> rowObfClasses = new ArrayList<>();
	private final List<Entry<?>> rowTargets = new ArrayList<>();

	public McpLogPanel(Gui gui) {
		super(new BorderLayout());
		this.gui = gui;

		this.model = new DefaultTableModel(0, 4) {
			@Override
			public boolean isCellEditable(int row, int column) {
				return false;
			}
		};

		this.table = new JTable(this.model);
		this.table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		this.table.setAutoCreateRowSorter(true);
		this.table.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(MouseEvent e) {
				if (e.getClickCount() == 2) {
					onRowActivated();
				}
			}
		});

		retranslateUi();
		this.scrollPane = new JScrollPane(this.table);
		this.add(this.scrollPane, BorderLayout.CENTER);
	}

	public final void retranslateUi() {
		this.model.setColumnIdentifiers(new Object[]{
				I18n.translate("mcp.log.column.time"),
				I18n.translate("mcp.log.column.operation"),
				I18n.translate("mcp.log.column.class"),
				I18n.translate("mcp.log.column.detail"),
		});
	}

	/**
	 * Appends a log row. Safe to call from any thread.
	 *
	 * @param operation the MCP tool name
	 * @param obfClass the affected obfuscated class full name, or {@code null}
	 * @param navTarget the resolved entry to jump to on double-click, or {@code null}
	 * @param detail a short result or error description
	 */
	public void log(String operation, String obfClass, Entry<?> navTarget, String detail) {
		SwingUtilities.invokeLater(() -> {
			boolean atBottom = isScrolledToBottom();

			if (this.model.getRowCount() >= MAX_ROWS) {
				this.model.removeRow(0);
				this.rowObfClasses.remove(0);
				this.rowTargets.remove(0);
			}

			this.model.addRow(new Object[]{LocalTime.now().format(TIME_FORMAT), operation, obfClass == null ? "" : obfClass, detail});
			this.rowObfClasses.add(obfClass);
			this.rowTargets.add(navTarget);

			if (atBottom) {
				int last = this.model.getRowCount() - 1;
				this.table.scrollRectToVisible(this.table.getCellRect(last, 0, true));
			}
		});
	}

	private boolean isScrolledToBottom() {
		JScrollBar bar = this.scrollPane.getVerticalScrollBar();

		if (bar == null || !bar.isVisible()) {
			return true;
		}

		return bar.getValue() + bar.getVisibleAmount() >= bar.getMaximum() - 2;
	}

	private void onRowActivated() {
		int viewRow = this.table.getSelectedRow();

		if (viewRow < 0) {
			return;
		}

		int modelRow = this.table.convertRowIndexToModel(viewRow);

		if (modelRow < 0 || modelRow >= this.rowTargets.size() || this.gui.getController().getProject() == null) {
			return;
		}

		Entry<?> target = this.rowTargets.get(modelRow);

		if (target == null) {
			String obfClass = this.rowObfClasses.get(modelRow);

			if (obfClass == null || obfClass.isEmpty()) {
				return;
			}

			target = new ClassEntry(obfClass);
		}

		try {
			this.gui.getController().navigateTo(target);
		} catch (RuntimeException ignored) {
			// invalid/unknown reference, nothing to navigate to
		}
	}
}
