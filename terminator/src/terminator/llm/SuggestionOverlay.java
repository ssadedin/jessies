package terminator.llm;

import java.awt.*;
import java.awt.datatransfer.*;
import javax.swing.*;
import javax.swing.border.*;
import javax.swing.text.*;
import terminator.*;

/**
 * The small panel in the top-right corner of a terminal that shows a suggestion's progress and text.
 * None of its components take keyboard focus, so typing always goes to the terminal. See section 8 of the plan.
 */
public final class SuggestionOverlay extends JPanel {
    private static final int MARGIN = 12;
    private static final int PADDING = 8;
    private static final int ARC = 10;

    private final JLabel status = new JLabel(" ");
    private final JTextArea body = new JTextArea();
    private final JScrollPane bodyScrollPane = new JScrollPane(body);
    private final JButton copyButton = new JButton("Copy");
    private final JLabel hint = new JLabel("Esc to close");
    private String copyText = "";
    private Color background = Color.WHITE;
    private Color borderColor = Color.GRAY;

    public SuggestionOverlay() {
        super(new BorderLayout(0, PADDING / 2));
        setOpaque(false);
        setVisible(false);
        setBorder(new EmptyBorder(PADDING, PADDING + 2, PADDING, PADDING + 2));

        body.setEditable(false);
        body.setLineWrap(true);
        body.setWrapStyleWord(true);
        body.setOpaque(false);
        ((DefaultCaret) body.getCaret()).setUpdatePolicy(DefaultCaret.NEVER_UPDATE);

        bodyScrollPane.setOpaque(false);
        bodyScrollPane.getViewport().setOpaque(false);
        bodyScrollPane.setBorder(null);
        bodyScrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);

        copyButton.addActionListener(event -> copyToClipboard());
        copyButton.setEnabled(false);

        JPanel footer = new JPanel(new BorderLayout());
        footer.setOpaque(false);
        footer.add(copyButton, BorderLayout.WEST);
        footer.add(hint, BorderLayout.EAST);

        add(status, BorderLayout.NORTH);
        add(bodyScrollPane, BorderLayout.CENTER);
        add(footer, BorderLayout.SOUTH);

        for (Component component : new Component[] { this, status, body, bodyScrollPane, bodyScrollPane.getViewport(), bodyScrollPane.getVerticalScrollBar(), copyButton, hint, footer }) {
            component.setFocusable(false);
        }
    }

    /**
     * Shows the overlay, empty, with the given status, styled to match the terminal.
     */
    public void start(String statusText, Font terminalFont, int fontPercent) {
        applyStyle(terminalFont, fontPercent);
        body.setText("");
        copyText = "";
        body.setForeground(foreground());
        copyButton.setEnabled(false);
        setHint("Esc to close");
        setStatus(statusText);
        setVisible(true);
        relayout();
    }

    public void setStatus(String statusText) {
        status.setText(statusText);
    }

    public void setHint(String hintText) {
        hint.setText(hintText);
    }

    /**
     * Copies what Copy copies (just the command, for a command suggestion). Returns false if there's nothing to copy.
     */
    public boolean copyToClipboard() {
        if (!isVisible() || copyText.isBlank()) {
            return false;
        }
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(copyText), null);
        setStatus("Copied to the clipboard");
        return true;
    }

    public void appendText(String text) {
        boolean wasAtBottom = isScrolledToBottom();
        body.append(text);
        copyText = body.getText();
        copyButton.setEnabled(!copyText.isBlank());
        relayout();
        if (wasAtBottom) {
            // Follow the text as it streams in, unless the user has scrolled up to read.
            SwingUtilities.invokeLater(() -> body.scrollRectToVisible(new Rectangle(0, body.getHeight() - 1, 1, 1)));
        }
    }

    public String getText() {
        return body.getText();
    }

    /**
     * Replaces the streamed text with the finished reply, so a command is shown, and copied, without its "COMMAND:" label.
     */
    public void showReply(String statusText, SuggestionReply reply) {
        setStatus(statusText);
        body.setText(reply.displayText());
        copyText = reply.copyText();
        copyButton.setEnabled(!copyText.isBlank());
        relayout();
    }

    /**
     * Explains why a command couldn't be inserted, below the command itself.
     */
    public void showNotInserted(SuggestionReply reply, String problem) {
        setStatus("Copied instead of inserting");
        setHint("Esc to close");
        body.setText(reply.displayText() + "\n\n(Not inserted because " + problem + ".)");
        relayout();
    }

    public void showError(String statusText, String message) {
        setStatus(statusText);
        body.setForeground(errorColor());
        body.setText(message);
        copyText = message;
        copyButton.setEnabled(true);
        setVisible(true);
        relayout();
    }

    public void close() {
        if (isVisible()) {
            setVisible(false);
            relayout();
        }
    }

    /**
     * Positions the overlay in the top-right corner of a parent of the given size, leaving rightInset
     * pixels clear for the terminal's scroll bar.
     */
    public void layoutWithin(int parentWidth, int parentHeight, int rightInset) {
        if (!isVisible()) {
            return;
        }
        int available = Math.max(0, parentWidth - rightInset - 2 * MARGIN);
        int charWidth = body.getFontMetrics(body.getFont()).charWidth('m');
        int width = Math.min(available, Math.max(parentWidth / 2, 40 * charWidth));

        Insets insets = getInsets();
        int textWidth = Math.max(1, width - insets.left - insets.right - bodyScrollPane.getVerticalScrollBar().getPreferredSize().width);
        // A wrapping JTextArea's preferred height depends on its width.
        body.setSize(textWidth, Short.MAX_VALUE);
        int bodyHeight = body.getPreferredSize().height;
        BorderLayout layout = (BorderLayout) getLayout();
        int chromeHeight = insets.top + insets.bottom + status.getPreferredSize().height + ((JComponent) layout.getLayoutComponent(BorderLayout.SOUTH)).getPreferredSize().height + 2 * layout.getVgap();
        int maxHeight = Math.max(chromeHeight + 3 * body.getFontMetrics(body.getFont()).getHeight(), parentHeight * 40 / 100);
        int height = Math.min(Math.max(0, parentHeight - 2 * MARGIN), Math.min(maxHeight, chromeHeight + bodyHeight + 2));

        setBounds(parentWidth - rightInset - MARGIN - width, MARGIN, width, height);
        validate();
    }

    @Override protected void paintComponent(Graphics oldGraphics) {
        Graphics2D g = (Graphics2D) oldGraphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(background);
            g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, ARC, ARC);
            g.setColor(borderColor);
            g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, ARC, ARC);
        } finally {
            g.dispose();
        }
        super.paintComponent(oldGraphics);
    }

    private void relayout() {
        Container parent = getParent();
        if (parent != null) {
            parent.revalidate();
            parent.repaint();
        }
    }

    private boolean isScrolledToBottom() {
        BoundedRangeModel model = bodyScrollPane.getVerticalScrollBar().getModel();
        return model.getValue() + model.getExtent() >= model.getMaximum() - 2;
    }

    private void applyStyle(Font terminalFont, int fontPercent) {
        float size = Math.max(8f, terminalFont.getSize2D() * fontPercent / 100f);
        Font font = terminalFont.deriveFont(size);
        Color terminalBackground = Terminator.getPreferences().getColor(TerminatorPreferences.BACKGROUND_COLOR);
        Color terminalForeground = foreground();
        background = mix(terminalBackground, terminalForeground, 0.08, 242);
        borderColor = Terminator.getPreferences().getColor(TerminatorPreferences.SELECTION_COLOR);

        body.setFont(font);
        hint.setFont(font);
        copyButton.setFont(font);
        status.setFont(font.deriveFont(Font.BOLD));
        status.setForeground(terminalForeground);
        hint.setForeground(mix(terminalForeground, terminalBackground, 0.4, 255));
        body.setSelectionColor(borderColor);
    }

    private static Color foreground() {
        return Terminator.getPreferences().getColor(TerminatorPreferences.FOREGROUND_COLOR);
    }

    private static Color errorColor() {
        // The palette's red, bright on dark backgrounds so it stays readable.
        Color background = Terminator.getPreferences().getColor(TerminatorPreferences.BACKGROUND_COLOR);
        boolean dark = (0.299 * background.getRed() + 0.587 * background.getGreen() + 0.114 * background.getBlue()) < 128;
        return Palettes.getColor(dark ? 9 : 1);
    }

    private static Color mix(Color base, Color other, double otherFraction, int alpha) {
        return new Color(
                (int) Math.round(base.getRed() * (1 - otherFraction) + other.getRed() * otherFraction),
                (int) Math.round(base.getGreen() * (1 - otherFraction) + other.getGreen() * otherFraction),
                (int) Math.round(base.getBlue() * (1 - otherFraction) + other.getBlue() * otherFraction),
                alpha);
    }
}
