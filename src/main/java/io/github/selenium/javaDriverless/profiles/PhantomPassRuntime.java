package io.github.selenium.javaDriverless.profiles;

/** Physical runtime selected by the PhantomPass adapter. */
public final class PhantomPassRuntime {
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

    public static final PhantomPassRuntime NATIVE_HOST = new PhantomPassRuntime(Kind.NATIVE_HOST, null);
    public static final PhantomPassRuntime VIRTUAL_DISPLAY =
            new PhantomPassRuntime(Kind.VIRTUAL_DISPLAY, null);

    private final Kind kind;
    private final Geometry geometry;

    private PhantomPassRuntime(Kind kind, Geometry geometry) {
        this.kind = kind;
        this.geometry = geometry;
    }

    public static PhantomPassRuntime virtualDisplay(Geometry geometry) {
        return new PhantomPassRuntime(Kind.VIRTUAL_DISPLAY, geometry);
    }

    public PhantomPassRuntime withGeometry(Geometry geometry) {
        return new PhantomPassRuntime(kind, geometry);
    }

    public Kind kind() {
        return kind;
    }

    public Geometry geometry() {
        return geometry;
    }
}
