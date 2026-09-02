package sugarcube.jexter.ocd.model;

/** A placed audio resource (e.g. {@code audio_0001.mp3}). No visual in static projections. */
public final class OCDAudio extends OCDMedia {

    public OCDAudio() {}
    public OCDAudio(String resourceRef) { this.resourceRef = resourceRef; }

    @Override public String tag() { return "audio"; }
}
