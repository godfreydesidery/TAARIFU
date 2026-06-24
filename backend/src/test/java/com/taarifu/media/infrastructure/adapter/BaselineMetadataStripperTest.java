package com.taarifu.media.infrastructure.adapter;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link BaselineMetadataStripper} — the dependency-free EXIF/metadata scrubber (A6,
 * PRD §21 EI-8, §18).
 *
 * <p>Responsibility: proves, with hand-built byte buffers and no external library, the load-bearing A6
 * behaviours: a JPEG's {@code APP1}/EXIF (and {@code COM}) segments are removed while {@code APP0}/JFIF
 * and the entropy-coded scan data are preserved; a PNG's {@code eXIf}/{@code tEXt} chunks are removed
 * while critical chunks survive; an already-clean image is returned byte-identical; an unhandled type is
 * passed through untouched; and malformed input is returned unchanged rather than corrupted (fail-safe).
 * Each test would fail if the strip silently kept the privacy segment, which is the point.</p>
 */
class BaselineMetadataStripperTest {

    private final BaselineMetadataStripper stripper = new BaselineMetadataStripper();

    // --- markers ---
    private static final int FF = 0xFF;
    private static final int SOI = 0xD8;
    private static final int EOI = 0xD9;
    private static final int SOS = 0xDA;
    private static final int APP0 = 0xE0;
    private static final int APP1 = 0xE1;
    private static final int COM = 0xFE;

    @Test
    void handles_jpegAndPng_butNotOthers() {
        assertThat(stripper.handles("image/jpeg")).isTrue();
        assertThat(stripper.handles("image/jpg")).isTrue();   // common alias
        assertThat(stripper.handles("IMAGE/PNG")).isTrue();   // case-insensitive
        assertThat(stripper.handles("application/pdf")).isFalse();
        assertThat(stripper.handles(null)).isFalse();
    }

    @Test
    void strip_jpeg_removesApp1Exif_keepsApp0AndScanData() {
        byte[] jpeg = jpeg(
                app0Jfif(),                       // structural — KEEP
                app1Exif(),                       // EXIF/GPS — DROP
                comment("secret-comment"),        // COM — DROP
                scan());                          // SOS + entropy data + EOI — KEEP

        byte[] out = stripper.strip("image/jpeg", jpeg);

        // The APP1/EXIF and COM segments are gone; APP0 and the scan data remain → still a valid JPEG.
        assertThat(hasMarker(out, APP1)).isFalse();
        assertThat(hasMarker(out, COM)).isFalse();
        assertThat(hasMarker(out, APP0)).isTrue();
        assertThat(hasMarker(out, SOS)).isTrue();
        // SOI is preserved at the head.
        assertThat(out[0] & 0xFF).isEqualTo(FF);
        assertThat(out[1] & 0xFF).isEqualTo(SOI);
        // The output is strictly smaller (we dropped segments) but non-empty.
        assertThat(out.length).isLessThan(jpeg.length).isGreaterThan(4);
    }

    @Test
    void strip_jpeg_withNoMetadata_isReturnedUnchanged() {
        // A JPEG that already has only structural segments + scan data must come back byte-identical (so the
        // service skips the needless re-store: scrubbed == original by reference is not required, equality is).
        byte[] jpeg = jpeg(app0Jfif(), scan());

        byte[] out = stripper.strip("image/jpeg", jpeg);

        assertThat(out).isEqualTo(jpeg);
    }

    @Test
    void strip_png_removesExifAndTextChunks_keepsCriticalChunks() {
        byte[] png = png(
                chunk("IHDR", new byte[]{0, 0, 0, 1, 0, 0, 0, 1, 8, 2, 0, 0, 0}), // critical — KEEP
                chunk("eXIf", new byte[]{1, 2, 3, 4}),                            // EXIF — DROP
                chunk("tEXt", "Comment\0hello".getBytes()),                       // text — DROP
                chunk("IDAT", new byte[]{9, 9, 9}),                               // critical — KEEP
                chunk("IEND", new byte[0]));                                      // critical — KEEP

        byte[] out = stripper.strip("image/png", png);

        assertThat(containsAscii(out, "eXIf")).isFalse();
        assertThat(containsAscii(out, "tEXt")).isFalse();
        assertThat(containsAscii(out, "IHDR")).isTrue();
        assertThat(containsAscii(out, "IDAT")).isTrue();
        assertThat(containsAscii(out, "IEND")).isTrue();
    }

    @Test
    void strip_unhandledType_returnsInputUnchanged() {
        byte[] pdf = {'%', 'P', 'D', 'F', '-', '1', '.', '4'};
        assertThat(stripper.strip("application/pdf", pdf)).isSameAs(pdf);
    }

    @Test
    void strip_malformedJpeg_returnsInputUnchanged_notCorrupted() {
        // Claims to be a JPEG but is not framed as one — fail safe (return as-is, never emit garbage).
        byte[] notJpeg = {0x01, 0x02, 0x03, 0x04, 0x05};
        assertThat(stripper.strip("image/jpeg", notJpeg)).isSameAs(notJpeg);
    }

    @Test
    void strip_nullBytes_throws() {
        assertThatThrownBy(() -> stripper.strip("image/jpeg", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // -------------------------------------------------------------------------------------------------
    // Byte builders
    // -------------------------------------------------------------------------------------------------

    /** Assembles SOI + the given segment byte-arrays into one JPEG buffer. */
    private static byte[] jpeg(byte[]... segments) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(FF);
        out.write(SOI);
        for (byte[] seg : segments) {
            out.write(seg, 0, seg.length);
        }
        return out.toByteArray();
    }

    /** An APP0/JFIF segment (structural). */
    private static byte[] app0Jfif() {
        byte[] payload = {'J', 'F', 'I', 'F', 0x00, 0x01, 0x01};
        return segment(APP0, payload);
    }

    /** An APP1 segment whose payload begins with the EXIF identifier (camera GPS lives here). */
    private static byte[] app1Exif() {
        byte[] payload = {'E', 'x', 'i', 'f', 0x00, 0x00, 0x44, 0x55, 0x66};
        return segment(APP1, payload);
    }

    /** A COM comment segment. */
    private static byte[] comment(String text) {
        return segment(COM, text.getBytes());
    }

    /** SOS marker + a couple of scan-data bytes + EOI (the entropy section the stripper copies verbatim). */
    private static byte[] scan() {
        return new byte[]{(byte) FF, (byte) SOS, 0x00, 0x00, 0x12, 0x34, (byte) FF, (byte) EOI};
    }

    /** Builds a length-bearing JPEG segment: FF marker + big-endian length(self+payload) + payload. */
    private static byte[] segment(int marker, byte[] payload) {
        int len = payload.length + 2;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(FF);
        out.write(marker);
        out.write((len >> 8) & 0xFF);
        out.write(len & 0xFF);
        out.write(payload, 0, payload.length);
        return out.toByteArray();
    }

    /** Assembles the PNG signature + the given chunks into one PNG buffer. */
    private static byte[] png(byte[]... chunks) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', (byte) 0x1A, '\n'}, 0, 8);
        for (byte[] c : chunks) {
            out.write(c, 0, c.length);
        }
        return out.toByteArray();
    }

    /** Builds a PNG chunk: length(4) + type(4) + data + crc(4) — CRC is a placeholder (not validated here). */
    private static byte[] chunk(String type, byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int len = data.length;
        out.write((len >> 24) & 0xFF);
        out.write((len >> 16) & 0xFF);
        out.write((len >> 8) & 0xFF);
        out.write(len & 0xFF);
        out.write(type.getBytes(), 0, 4);
        out.write(data, 0, data.length);
        out.write(new byte[]{0, 0, 0, 0}, 0, 4); // placeholder CRC
        return out.toByteArray();
    }

    /** @return {@code true} if the JPEG bytes contain the marker {@code 0xFF <marker>} anywhere. */
    private static boolean hasMarker(byte[] data, int marker) {
        for (int i = 0; i + 1 < data.length; i++) {
            if ((data[i] & 0xFF) == FF && (data[i + 1] & 0xFF) == marker) {
                return true;
            }
        }
        return false;
    }

    /** @return {@code true} if the ASCII string appears in the bytes (used to detect a PNG chunk type). */
    private static boolean containsAscii(byte[] data, String ascii) {
        byte[] needle = ascii.getBytes();
        outer:
        for (int i = 0; i + needle.length <= data.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (data[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }
}
