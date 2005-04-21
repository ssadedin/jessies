package e.ptextarea;


import java.awt.*;

/**
 * A PLineSegment represents a section of text which should be painted in the
 * text component.  It knows about styles, the text it contains, and can calculate
 * how wide its text should appear.
 *
 * There are two implementations of this class: one to handle tab characters, and one
 * for normal text.
 * 
 * @author Phil Norman
 */

public interface PLineSegment {
    /** Returns the style to be used when painting this text. */
    public PStyle getStyle();
    
    /** Returns the char sequence represented by this segment. */
    public CharSequence getCharSequence();
    
    /** Returns the text to be drawn. */
    public String getText();
    
    public PLineSegment subSegment(int start);
    
    public PLineSegment subSegment(int start, int end);
    
    /** Returns the text offset of the start of this segment. */
    public int getOffset();
    
    /** Returns the text offset of the character just after the end of this segment. */
    public int getEnd();
    
    /** Returns the number of characters in the text. */
    public int getLength();
    
    /** Returns true if this segment represents any line break, be it caused by line wrap or a newline character. */
    public boolean isNewline();
    
    /** Returns true only if this segment represents a hard newline (one representing a newline character). */
    public boolean isHardNewline();
    
    /** Returns the width of this text in pixels, when painted from the given X position in pixels. */
    public int getDisplayWidth(FontMetrics metrics, int startX);
    
    /**
     * Returns the width between the start of this segment, and the given character offset within
     * the segment, when painted from the given X position, measured in pixels.
     */
    public int getDisplayWidth(FontMetrics metrics, int startX, int charOffset);
    
    /**
     * Returns the character offset from the start of this segment which is nearest to the given
     * 'x' position when this segment is drawn from the given startX position, measured in pixels.
     */
    public int getCharOffset(FontMetrics metrics, int startX, int x);
    
    /**
     * Paints the text into the given location.
     */
    public void paint(Graphics2D graphics, int x, int yBaseline);
}
