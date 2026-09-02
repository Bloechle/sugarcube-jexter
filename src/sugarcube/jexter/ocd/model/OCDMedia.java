package sugarcube.jexter.ocd.model;

import sugarcube.jexter.core.JxRect;

/**
 * Embedded time-based media placed on a page. The node is the placement (a region
 * obtained by mapping the unit square through the node {@link #transform}) plus
 * playback intent; the bytes live once in the document media resources (keyed by
 * {@link #resourceRef}, see {@code media/} in the container). Two leaves:
 * {@link OCDVideo} and {@link OCDAudio}.
 *
 * <p>Media projects naturally to the web (HTML {@code <video>}/{@code <audio>});
 * in the static facsimile projections a video shows its poster frame and audio
 * has no visual.
 */
public sealed abstract class OCDMedia extends OCDNode permits OCDAudio, OCDVideo {

    protected static final JxRect UNIT = new JxRect(0, 0, 1, 1);

    protected String  resourceRef;          // key into document media resources, e.g. "video_0001.mp4"
    protected boolean controls = true;       // expose playback controls
    protected boolean autoplay = false;
    protected boolean loop     = false;
    protected boolean muted    = false;

    public String   resourceRef()         { return resourceRef; }
    public OCDMedia resourceRef(String r) { this.resourceRef = r; return this; }
    public boolean  controls()            { return controls; }
    public OCDMedia controls(boolean v)   { this.controls = v; return this; }
    public boolean  autoplay()            { return autoplay; }
    public OCDMedia autoplay(boolean v)   { this.autoplay = v; return this; }
    public boolean  loop()                { return loop; }
    public OCDMedia loop(boolean v)       { this.loop = v; return this; }
    public boolean  muted()               { return muted; }
    public OCDMedia muted(boolean v)      { this.muted = v; return this; }

    /** Element tag / kind: {@code "audio"} or {@code "video"}. */
    public abstract String tag();

    /** Container format from the resource extension ({@code "mp4"} / {@code "mp3"}). */
    public String format() {
        if (resourceRef == null) return null;
        int dot = resourceRef.lastIndexOf('.');
        return dot < 0 ? null : resourceRef.substring(dot + 1).toLowerCase();
    }

    @Override public JxRect bounds() { return transform.apply(UNIT); }
}
