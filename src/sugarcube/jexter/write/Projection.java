package sugarcube.jexter.write;

import sugarcube.jexter.convert.ConvertOptions;
import sugarcube.jexter.ocd.model.OCDDocument;

import java.io.IOException;
import java.io.OutputStream;

/**
 * The one contract every projection shares: turn an {@link OCDDocument} into a stream of bytes,
 * configured by a single {@link ConvertOptions}. Text projections (SVG, HTML, Markdown, DocTags)
 * encode UTF-8; binary projections (PDF, EPUB) write their container bytes directly. Whole-document
 * writers ignore {@link ConvertOptions#PAGE}; the per-page SVG projection honors it.
 *
 * <p>This is what lets {@link Conversion} treat all writers identically — one method reference per
 * target, no per-writer output-shape adapters. Each writer keeps its richer core (a {@code String}
 * for the text projections, a per-page {@code render} for SVG); this is the uniform façade over it.
 */
@FunctionalInterface
public interface Projection {
    void write(OCDDocument doc, OutputStream out, ConvertOptions opt) throws IOException;
}
