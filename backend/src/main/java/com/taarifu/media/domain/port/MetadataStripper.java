package com.taarifu.media.domain.port;

/**
 * Outbound port that removes privacy-sensitive metadata from an image's bytes before it may be served
 * (A6 — PRD §21 EI-8, §18; ARCHITECTURE.md §3.3/§7).
 *
 * <p>Responsibility: abstracts "scrub the embedded metadata from these image bytes" so the
 * quarantine-then-serve flow can guarantee no photo is delivered to another citizen still carrying GPS
 * coordinates, device identifiers, timestamps, or other EXIF/XMP data. The civic {@code Report} captures
 * (and access-controls) incident geo separately and deliberately; a photo's embedded EXIF GPS would
 * <b>leak the reporter's exact location</b> for a sensitive or anonymous report and bypass that control,
 * so it must be stripped at the source.</p>
 *
 * <p><b>WHY a port (not an inline utility).</b> The scrub engine is a swappable concern: the shipped
 * baseline is a dependency-free marker/chunk stripper (JPEG/PNG); a future adapter could delegate to a
 * hardened imaging library or a re-encode pass. Keeping it behind a port lets the strip strategy evolve
 * without touching the {@code MediaService} worker (SOLID — depend on the abstraction).</p>
 *
 * <p><b>Fail-safe contract.</b> An implementation must <b>never silently return un-scrubbed bytes for a
 * format it claims to handle</b>: for a supported image type it returns metadata-free bytes; for a type
 * it does not understand it returns the bytes unchanged (there is nothing it can safely strip) — the
 * serve path still gates on the format being one this stripper handles, so an un-handleable type never
 * becomes servable as an image. It must never corrupt valid image bytes.</p>
 */
public interface MetadataStripper {

    /**
     * Returns a copy of {@code bytes} with privacy-sensitive metadata removed, when the content type is an
     * image format this stripper handles; otherwise returns the input unchanged.
     *
     * @param contentType the declared MIME type (e.g. {@code image/jpeg}); may be {@code null}.
     * @param bytes       the original object bytes (already size-capped by upload policy).
     * @return the scrubbed bytes for a handled format, or the original bytes for an unhandled one.
     * @throws IllegalArgumentException if {@code bytes} is {@code null}.
     */
    byte[] strip(String contentType, byte[] bytes);

    /**
     * @param contentType the declared MIME type; may be {@code null}.
     * @return {@code true} if this stripper actively scrubs metadata for the given type — i.e. a successful
     *         {@link #strip} on it produces genuinely metadata-free bytes. The serve-path invariant uses
     *         this to decide which uploads <i>must</i> be proven stripped before they may be served, so an
     *         image format the stripper cannot scrub is never served as if it had been (fail-safe).
     */
    boolean handles(String contentType);
}
