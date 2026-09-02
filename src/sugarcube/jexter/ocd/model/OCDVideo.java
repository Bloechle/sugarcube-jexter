package sugarcube.jexter.ocd.model;

/** A placed video resource (e.g. {@code video_0001.mp4}) with an optional poster image. */
public final class OCDVideo extends OCDMedia {

    private String poster;                  // optional image resource ref for the poster frame

    public OCDVideo() {}
    public OCDVideo(String resourceRef) { this.resourceRef = resourceRef; }

    public String   poster()           { return poster; }
    public OCDVideo poster(String ref) { this.poster = ref; return this; }

    @Override public String tag() { return "video"; }
}
