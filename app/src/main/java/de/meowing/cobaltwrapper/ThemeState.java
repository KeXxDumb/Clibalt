package de.meowing.cobaltwrapper;

/**
 * Guarda en memoria la última paleta detectada del tema real de cobalt
 * (claro u oscuro), para que tanto la actividad principal como el bottom
 * sheet de historial puedan pintarse acorde sin depender del modo del
 * sistema operativo.
 */
public class ThemeState {
    public static boolean isDark = true;

    public static int background = 0xFF000000;
    public static int surface = 0xFF121212;
    public static int textPrimary = 0xFFF0F0F0;
    public static int textSecondary = 0xFFAAAAAA;
    public static int divider = 0xFF3A3A3A;

    private static final int[] LIGHT = {0xFFF4F4F4, 0xFFFFFFFF, 0xFF1A1A1A, 0xFF767676, 0xFFE0E0E0};
    private static final int[] DARK  = {0xFF000000, 0xFF121212, 0xFFF0F0F0, 0xFFAAAAAA, 0xFF3A3A3A};

    public static void apply(boolean dark) {
        isDark = dark;
        int[] palette = dark ? DARK : LIGHT;
        background = palette[0];
        surface = palette[1];
        textPrimary = palette[2];
        textSecondary = palette[3];
        divider = palette[4];
    }
}
