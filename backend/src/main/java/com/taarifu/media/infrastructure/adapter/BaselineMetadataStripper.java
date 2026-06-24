package com.taarifu.media.infrastructure.adapter;

import com.taarifu.media.domain.port.MetadataStripper;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.util.Locale;

/**
 * Dependency-free {@link MetadataStripper} that removes privacy-sensitive metadata from JPEG and PNG
 * bytes by editing their container structure directly (A6 — PRD §21 EI-8, §18; ARCHITECTURE.md §7).
 *
 * <p>Responsibility: ship a metadata scrubber that works identically in dev (in-memory store) and
 * production (S3) with <b>no new third-party dependency on the classpath</b> and no native image
 * re-encode (which would degrade the photo and burn CPU). It operates purely on the well-defined,
 * stable container framing of each format, dropping the segments/chunks that carry EXIF (incl. GPS),
 * XMP, ICC, Photoshop IRB, comments, and timestamps while preserving the image data verbatim — so the
 * served photo is pixel-identical to the upload, minus its metadata.</p>
 *
 * <p><b>JPEG.</b> A JPEG is a stream of marker segments. The privacy payloads live in the {@code APPn}
 * application segments — {@code APP1} carries EXIF and XMP, {@code APP2} ICC, {@code APP13} Photoshop/IPTC
 * — and in {@code COM} comment segments. This stripper copies the {@code SOI} and every segment up to the
 * start of scan ({@code SOS}) <b>except</b> {@code APP1..APP15} and {@code COM}; from {@code SOS} onward
 * the entropy-coded image data is copied verbatim. {@code APP0} (JFIF), {@code DQT}, {@code DHT},
 * {@code SOFn}, and {@code DRI} are structural and kept. The result is a valid, displayable JPEG with no
 * EXIF/GPS.</p>
 *
 * <p><b>PNG.</b> A PNG is a signature followed by length-tagged chunks. EXIF lives in the ancillary
 * {@code eXIf} chunk and textual metadata in {@code tEXt}/{@code iTXt}/{@code zTXt}; the modification time
 * is {@code tIME}. This stripper copies every chunk <b>except</b> those, preserving the critical chunks
 * ({@code IHDR}/{@code PLTE}/{@code IDAT}/{@code IEND}) and any rendering-relevant ancillary chunks.</p>
 *
 * <p><b>Fail-safe.</b> If the bytes do not parse as the claimed format (truncated, not actually a
 * JPEG/PNG, or a marker runs past the buffer), the stripper returns the input unchanged rather than
 * emit corrupt bytes — and {@link #handles(String)} still reports the type as one it scrubs, so the
 * caller's serve-path invariant decides what to do. It never throws into the worker path on malformed
 * input (only on a {@code null} argument, a programming error).</p>
 */
@Component
public class BaselineMetadataStripper implements MetadataStripper {

    // --- JPEG markers (each preceded by 0xFF) ---
    private static final int MARKER_PREFIX = 0xFF;
    private static final int SOI = 0xD8;   // Start Of Image
    private static final int EOI = 0xD9;   // End Of Image
    private static final int SOS = 0xDA;   // Start Of Scan (entropy data follows — copy verbatim to EOI)
    private static final int APP0 = 0xE0;  // JFIF — structural, KEEP
    private static final int APP1 = 0xE1;  // EXIF / XMP — privacy, DROP (lowest of the APPn drop range)
    private static final int APP15 = 0xEF; // highest APPn — DROP up to here
    private static final int COM = 0xFE;   // Comment — DROP

    // --- PNG ---
    private static final byte[] PNG_SIGNATURE =
            {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', (byte) 0x1A, '\n'};

    @Override
    public byte[] strip(String contentType, byte[] bytes) {
        if (bytes == null) {
            throw new IllegalArgumentException("bytes must not be null");
        }
        String type = normalize(contentType);
        if (isJpeg(type)) {
            return stripJpeg(bytes);
        }
        if (isPng(type)) {
            return stripPng(bytes);
        }
        // Unhandled type: nothing this stripper can safely remove — return unchanged (the serve-path
        // invariant gates separately on handles()).
        return bytes;
    }

    @Override
    public boolean handles(String contentType) {
        String type = normalize(contentType);
        return isJpeg(type) || isPng(type);
    }

    /**
     * Strips {@code APP1..APP15} and {@code COM} segments from a JPEG, copying everything else verbatim.
     *
     * <p>Walks the marker stream from {@code SOI}: each non-entropy marker is followed by a 2-byte
     * big-endian length covering its own payload; standalone markers ({@code SOI}/{@code EOI}/{@code RSTn})
     * carry no length. Once {@code SOS} is reached the rest of the stream (compressed scan data plus the
     * trailing {@code EOI}) is copied as-is. On any malformed framing the original bytes are returned.</p>
     *
     * @param data the original JPEG bytes.
     * @return the scrubbed JPEG, or {@code data} unchanged if it is not a well-formed JPEG.
     */
    private byte[] stripJpeg(byte[] data) {
        // Must start with SOI (FF D8).
        if (data.length < 2 || (data[0] & 0xFF) != MARKER_PREFIX || (data[1] & 0xFF) != SOI) {
            return data;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length);
        out.write(MARKER_PREFIX);
        out.write(SOI);

        int i = 2;
        while (i + 1 < data.length) {
            if ((data[i] & 0xFF) != MARKER_PREFIX) {
                // Not aligned on a marker — malformed; fail safe.
                return data;
            }
            int marker = data[i + 1] & 0xFF;

            // Start of scan: copy the remainder (entropy-coded data + EOI) verbatim and finish.
            if (marker == SOS) {
                out.write(data, i, data.length - i);
                return out.toByteArray();
            }
            // Standalone markers without a length payload (EOI and the restart markers RST0..RST7).
            if (marker == EOI || (marker >= 0xD0 && marker <= 0xD7)) {
                out.write(MARKER_PREFIX);
                out.write(marker);
                i += 2;
                continue;
            }
            // Length-bearing segment: next two bytes are a big-endian length covering the length field
            // and the payload (but not the 2 marker bytes).
            if (i + 3 >= data.length) {
                return data; // truncated length — fail safe.
            }
            int segLength = ((data[i + 2] & 0xFF) << 8) | (data[i + 3] & 0xFF);
            int segEnd = i + 2 + segLength; // index just past the segment payload
            if (segLength < 2 || segEnd > data.length) {
                return data; // length runs past the buffer — fail safe.
            }

            boolean drop = (marker >= APP1 && marker <= APP15) || marker == COM;
            if (!drop) {
                // Keep the whole segment: 2 marker bytes + length field + payload.
                out.write(data, i, 2 + segLength);
            }
            i = segEnd;
        }
        // Reached the end without an SOS (e.g. metadata-only stream) — return what we accumulated.
        return out.toByteArray();
    }

    /**
     * Strips the metadata-bearing ancillary chunks ({@code eXIf}, {@code tEXt}, {@code iTXt}, {@code zTXt},
     * {@code tIME}) from a PNG, copying every other chunk (and the signature) verbatim.
     *
     * <p>Each chunk is {@code [4-byte big-endian length][4-byte type][length bytes data][4-byte CRC]}.
     * Dropped chunks are ancillary, so removing them yields a valid PNG with no recomputation needed for
     * the remaining chunks (their CRCs are unchanged). On malformed framing the original bytes are
     * returned.</p>
     *
     * @param data the original PNG bytes.
     * @return the scrubbed PNG, or {@code data} unchanged if it is not a well-formed PNG.
     */
    private byte[] stripPng(byte[] data) {
        if (!startsWith(data, PNG_SIGNATURE)) {
            return data;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length);
        out.write(PNG_SIGNATURE, 0, PNG_SIGNATURE.length);

        int i = PNG_SIGNATURE.length;
        while (i + 8 <= data.length) {
            int length = ((data[i] & 0xFF) << 24) | ((data[i + 1] & 0xFF) << 16)
                    | ((data[i + 2] & 0xFF) << 8) | (data[i + 3] & 0xFF);
            if (length < 0) {
                return data; // implausible / overflowed length — fail safe.
            }
            int chunkTotal = 12 + length; // length(4) + type(4) + data(length) + crc(4)
            if ((long) i + chunkTotal > data.length) {
                return data; // chunk runs past the buffer — fail safe.
            }
            String type = new String(data, i + 4, 4, java.nio.charset.StandardCharsets.US_ASCII);
            if (!isDroppablePngChunk(type)) {
                out.write(data, i, chunkTotal);
            }
            i += chunkTotal;
        }
        return out.toByteArray();
    }

    /** @return {@code true} for the PNG ancillary chunks that carry EXIF/text/timestamp metadata. */
    private boolean isDroppablePngChunk(String type) {
        return switch (type) {
            case "eXIf", "tEXt", "iTXt", "zTXt", "tIME" -> true;
            default -> false;
        };
    }

    /** Lower-cases and trims a content type for comparison; {@code null} becomes empty. */
    private String normalize(String contentType) {
        return contentType == null ? "" : contentType.trim().toLowerCase(Locale.ROOT);
    }

    /** @return whether the normalized type is a JPEG MIME type (covers the {@code image/jpg} alias). */
    private boolean isJpeg(String type) {
        return type.equals("image/jpeg") || type.equals("image/jpg");
    }

    /** @return whether the normalized type is a PNG MIME type. */
    private boolean isPng(String type) {
        return type.equals("image/png");
    }

    /** @return whether {@code data} begins with the given prefix bytes. */
    private boolean startsWith(byte[] data, byte[] prefix) {
        if (data.length < prefix.length) {
            return false;
        }
        for (int k = 0; k < prefix.length; k++) {
            if (data[k] != prefix[k]) {
                return false;
            }
        }
        return true;
    }
}
