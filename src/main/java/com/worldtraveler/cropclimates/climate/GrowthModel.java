package com.worldtraveler.cropclimates.climate;

/**
 * Pure growth math, no Minecraft types, so it can be pinned by unit tests.
 */
public final class GrowthModel {

    private GrowthModel() {
    }

    /**
     * Temperature fit. Peaks at the band centre (1.0), tapers even inside the
     * band down to 0.72 at either edge, then halves every {@code tol} beyond it.
     */
    public static double fit(double v, double lo, double hi, double tol) {
        if (v >= lo && v <= hi) {
            double half = (hi - lo) / 2.0;
            if (half <= 0) {
                return 1.0;
            }
            double mid = (lo + hi) / 2.0;
            double off = Math.abs(v - mid) / half;
            if (off > 1) {
                off = 1;
            }
            return 1.0 - 0.28 * off * off;
        }
        double d = (v < lo) ? (lo - v) : (v - hi);
        return 0.72 * Math.pow(0.5, d / tol);
    }

    /**
     * Humidity fit. Flat 1.0 anywhere inside the band; only crossing an edge
     * costs anything, falling off from that edge and halving every {@code tol}.
     */
    public static double fitEdge(double v, double lo, double hi, double tol) {
        if (v >= lo && v <= hi) {
            return 1.0;
        }
        double d = (v < lo) ? (lo - v) : (v - hi);
        return Math.pow(0.5, d / tol);
    }

    /** {@code floor + (max - floor) * (fitT * fitM) ^ curve}. */
    public static double total(double fitT, double fitM, double floor, double max, double curve) {
        double fit = fitT * fitM;
        return floor + (max - floor) * Math.pow(fit, curve);
    }
}
