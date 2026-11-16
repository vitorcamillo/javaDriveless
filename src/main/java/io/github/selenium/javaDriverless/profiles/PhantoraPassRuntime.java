package io.github.selenium.javaDriverless.profiles;

/** Physical runtime selected by the PhantoraPass adapter. */
public final class PhantoraPassRuntime {
    public enum Kind { NATIVE_HOST, VIRTUAL_DISPLAY }

    public record Geometry(
            int width,
            int height,
            int availWidth,
            int availHeight,
            int colorDepth,
            int pixelDepth,
            double deviceScaleFactor) {
    }

    public static final PhantoraPassRuntime NATIVE_HOST = new PhantoraPassRuntime(Kind.NATIVE_HOST, null);
    public static final PhantoraPassRuntime VIRTUAL_DISPLAY =
            new PhantoraPassRuntime(Kind.VIRTUAL_DISPLAY, null);

    private final Kind kind;
    private final Geometry geometry;

    private PhantoraPassRuntime(Kind kind, Geometry geometry) {
        this.kind = kind;
        this.geometry = geometry;
    }

    public static PhantoraPassRuntime virtualDisplay(Geometry geometry) {
        return new PhantoraPassRuntime(Kind.VIRTUAL_DISPLAY, geometry);
    }

    public PhantoraPassRuntime withGeometry(Geometry geometry) {
        return new PhantoraPassRuntime(kind, geometry);
    }

    public Kind kind() {
        return kind;
    }

    public Geometry geometry() {
        return geometry;
    }
}
