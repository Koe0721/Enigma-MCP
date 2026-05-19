package cuchaz.enigma.gui.dialog;

import java.awt.Component;
import java.awt.Dimension;
import java.awt.Frame;
import java.util.Arrays;
import java.util.List;

import javax.swing.JCheckBox;

import cuchaz.enigma.gui.config.McpConfig;
import cuchaz.enigma.gui.elements.ValidatableTextField;
import cuchaz.enigma.gui.util.ScaleUtil;
import cuchaz.enigma.utils.Pair;
import cuchaz.enigma.utils.validation.StandardValidation;

public class McpServerDialog extends AbstractDialog {
	private ValidatableTextField portField;
	private JCheckBox autoStartBox;
	private JCheckBox bindAllBox;

	public McpServerDialog(Frame owner) {
		super(owner, "menu.mcp", "prompt.ok", "prompt.cancel");

		Dimension preferredSize = getPreferredSize();
		preferredSize.width = ScaleUtil.scale(400);
		setPreferredSize(preferredSize);
		pack();
		setLocationRelativeTo(owner);
	}

	@Override
	protected List<Pair<String, Component>> createComponents() {
		portField = new ValidatableTextField(Integer.toString(McpConfig.getPort()));
		autoStartBox = new JCheckBox("", McpConfig.isAutoStart());
		bindAllBox = new JCheckBox("", McpConfig.isBindAll());

		portField.addActionListener(event -> confirm());

		return Arrays.asList(
				new Pair<>("prompt.mcp.port", portField),
				new Pair<>("prompt.mcp.autostart", autoStartBox),
				new Pair<>("prompt.mcp.bindall", bindAllBox));
	}

	@Override
	public void validateInputs() {
		vc.setActiveElement(portField);
		StandardValidation.isIntInRange(vc, portField.getText(), 1, 65535);
	}

	public Result getResult() {
		if (!isActionConfirm()) {
			return null;
		}

		vc.reset();
		validateInputs();

		if (!vc.canProceed()) {
			return null;
		}

		return new Result(Integer.parseInt(portField.getText()), autoStartBox.isSelected(), bindAllBox.isSelected());
	}

	public static Result show(Frame parent) {
		McpServerDialog dialog = new McpServerDialog(parent);

		dialog.setVisible(true);
		Result result = dialog.getResult();

		dialog.dispose();
		return result;
	}

	public static class Result {
		private final int port;
		private final boolean autoStart;
		private final boolean bindAll;

		public Result(int port, boolean autoStart, boolean bindAll) {
			this.port = port;
			this.autoStart = autoStart;
			this.bindAll = bindAll;
		}

		public int getPort() {
			return port;
		}

		public boolean isAutoStart() {
			return autoStart;
		}

		public boolean isBindAll() {
			return bindAll;
		}
	}
}
