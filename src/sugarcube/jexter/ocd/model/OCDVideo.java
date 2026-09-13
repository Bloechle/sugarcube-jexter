package sugarcube.jexter.ocd.model;

/** A placed video resource (e.g. {@code video_0001.mp4}) with an optional poster image. */
public final class OCDVideo extends OCDMedia {

    private String poster;                  // optional image resource ref for the poster frame

    public OCDVideo() {}
    public OCDVideo(String resourceRef) { this.resourceRef = resourceRef; }

    public String   poster()           { return poster; }
    public OCDVideo poster(String ref) { this.poster = ref; return this; }

    @Override public OCDVideo copy() {
        OCDVideo v = copyInto(new OCDVideo(resourceRef));
        v.controls = controls; v.autoplay = autoplay; v.loop = loop; v.muted = muted; v.poster = poster;
        return v;
    }

    @Override public String tag() { return "video"; }
}
