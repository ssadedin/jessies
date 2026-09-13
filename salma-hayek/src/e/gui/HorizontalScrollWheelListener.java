package e.gui;

import java.awt.event.*;
import javax.swing.*;
import org.jessies.test.*;

/**
 * Supports Mac-style horizontal scrolling using the scroll wheel with shift held down.
 * This isn't quite as clever as the real JScrollPane wheel scrolling code, but it's good enough (and actually delegates much of the work to Swing).
 * This should be obsoleted by any fix to Sun bug 6440198.
 */
public class HorizontalScrollWheelListener implements MouseWheelListener {
    public static final HorizontalScrollWheelListener INSTANCE = new HorizontalScrollWheelListener();
    
    private HorizontalScrollWheelListener() {
    }
    
    public void mouseWheelMoved(MouseWheelEvent e) {
        JScrollPane scrollPane = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, e.getComponent());
        if (scrollPane == null) {
            return;
        }
        
        if ((e.getModifiersEx() & InputEvent.SHIFT_DOWN_MASK) != 0) {
            if (e.getWheelRotation() == 0) {
                return;
            }
            
            JScrollBar scrollBar = scrollPane.getHorizontalScrollBar();
            if (scrollBar == null || scrollBar.isVisible() == false) {
                return;
            }
            
            int direction = Integer.signum(e.getWheelRotation());
            if (e.getScrollType() == MouseWheelEvent.WHEEL_UNIT_SCROLL) {
                scrollByUnits(scrollBar, direction, Math.abs(e.getUnitsToScroll()), (Math.abs(e.getWheelRotation()) == 1));
            } else if (e.getScrollType() == MouseWheelEvent.WHEEL_BLOCK_SCROLL) {
                scrollBar.setValue(clampedSum(scrollBar, scrollBar.getValue(), direction * scrollBar.getBlockIncrement(direction)));
            }
        } else {
            // Probably a vertical scroll wheel event, so let the enclosing scroll pane deal with it.
            scrollPane.dispatchEvent(e);
        }
    }
    
    /**
     * Equivalent to the package-private BasicScrollBarUI.scrollByUnits, which Swing uses for vertical wheel scrolling.
     * If limitToBlock is true, we don't scroll further than one block increment (after the first unit).
     */
    private static void scrollByUnits(JScrollBar scrollBar, int direction, int units, boolean limitToBlock) {
        final int start = scrollBar.getValue();
        final int limit = clampedSum(scrollBar, start, direction * scrollBar.getBlockIncrement(direction));
        for (int i = 0; i < units; ++i) {
            int oldValue = scrollBar.getValue();
            int newValue = clampedSum(scrollBar, oldValue, direction * scrollBar.getUnitIncrement(direction));
            if (newValue == oldValue) {
                break;
            }
            if (limitToBlock && i > 0 && (direction < 0 ? newValue < limit : newValue > limit)) {
                break;
            }
            scrollBar.setValue(newValue);
        }
    }
    
    @Test private static void testScrollByUnits() {
        // Values 0..1000 with 100 visible, so the largest reachable value is 900.
        JScrollBar scrollBar = new JScrollBar(JScrollBar.HORIZONTAL, 0, 100, 0, 1000);
        scrollBar.setUnitIncrement(10);
        scrollBar.setBlockIncrement(25);
        scrollByUnits(scrollBar, 1, 2, false);
        Assert.equals(scrollBar.getValue(), 20);
        // Limited to one block increment (25) beyond the start, after the first unit.
        scrollByUnits(scrollBar, 1, 5, true);
        Assert.equals(scrollBar.getValue(), 40);
        scrollByUnits(scrollBar, -1, 100, false);
        Assert.equals(scrollBar.getValue(), 0);
        scrollByUnits(scrollBar, 1, 1000, false);
        Assert.equals(scrollBar.getValue(), 900);
    }
    
    private static int clampedSum(JScrollBar scrollBar, int value, int delta) {
        long sum = (long) value + delta;
        return (int) Math.max(scrollBar.getMinimum(), Math.min(scrollBar.getMaximum(), sum));
    }
}
