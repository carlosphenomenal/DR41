package io.github.carlosphenomenal.dr41.plan;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns a test type + peak VUs + total duration into k6 ramping-vus stages.
 *
 * <pre>
 * LOAD   ramp 20% | hold 60% | ramp-down 20%
 * SOAK   ramp  5% | hold 90% | ramp-down  5%
 * STRESS 5 steps (20%,40%,60%,80%,100% of peak), each ramp+hold, then ramp-down
 * SPIKE  10% baseline -> sudden jump to peak -> back to baseline (recovery) -> ramp-down
 * SMOKE  constant small load
 * </pre>
 */
public final class LoadProfiles {
    private LoadProfiles() {}

    public static List<Stage> preset(TestType type, int vus, int total) {
        return switch (type) {
            case SMOKE -> List.of(new Stage(0, vus), new Stage(total, vus));
            case LOAD -> build(total, new double[]{.20, .60, .20}, new int[]{vus, vus, 0});
            case SOAK -> build(total, new double[]{.05, .90, .05}, new int[]{vus, vus, 0});
            case STRESS -> stress(total, vus);
            case SPIKE -> {
                int base = Math.max(1, vus / 10);
                yield build(total, new double[]{.05, .20, .05, .20, .05, .35, .10},
                        new int[]{base, base, vus, vus, base, base, 0});
            }
            case CUSTOM -> throw new IllegalArgumentException("CUSTOM requires explicit stages");
        };
    }

    private static List<Stage> stress(int total, int vus) {
        int steps = 5;
        double[] w = new double[steps * 2 + 1];
        int[] t = new int[steps * 2 + 1];
        for (int k = 1; k <= steps; k++) {
            int level = Math.max(1, (int) Math.ceil(vus * k / (double) steps));
            w[(k - 1) * 2] = .045;
            t[(k - 1) * 2] = level;
            w[(k - 1) * 2 + 1] = .135;
            t[(k - 1) * 2 + 1] = level;
        }
        w[steps * 2] = .10;
        t[steps * 2] = 0;
        return build(total, w, t);
    }

    /** Splits {@code total} seconds by weights using cumulative rounding so the sum is exact. */
    private static List<Stage> build(int total, double[] weights, int[] targets) {
        List<Stage> out = new ArrayList<>(weights.length);
        double cum = 0;
        int prev = 0;
        for (int i = 0; i < weights.length; i++) {
            cum += weights[i];
            int end = i == weights.length - 1 ? total : (int) Math.round(cum * total);
            out.add(new Stage(end - prev, targets[i]));
            prev = end;
        }
        return out;
    }
}
