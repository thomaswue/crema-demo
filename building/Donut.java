import java.util.Arrays;

public class Donut {
    public static void main(String[] args) {
        final int w = 80, hgt = 22, size = w * hgt;
        final char[] shades = ".,-~:;=!*#$@".toCharArray();
        final double Kx = 30, Ky = 15, Kz = 5;
        final double stepTheta = 0.02, stepPhi = 0.07, stepA = 0.04, stepB = 0.02;
        double e = 1, a = 0; // cosA, sinA
        double c = 1, d = 0; // cosB, sinB
        double[] z = new double[size];
        char[] buf = new char[size];
        System.out.print("\u001b[2J"); // ANSI escape code - clear console
        while (true) {
            for (int i = 0; i < size; i++) { buf[i] = ' '; z[i] = 0; }
            double h = 1, g = 0; // phi: (cos, sin)
            for (int j = 0; j < 90; j++) {
                double H = 1, G = 0; // theta: (cos, sin)
                for (int i = 0; i < 314; i++) {
                    double A = h + 2.0;
                    double denom = (G * A * a + g * e + Kz);
                    double D = 1.0 / denom;
                    double t = G * A * e - g * a;
                    int x = (int) (40 + Kx * D * (H * A * d - t * c));
                    int y = (int) (12 + Ky * D * (H * A * c + t * d));
                    if (y > 0 && y < hgt && x > 0 && x < w) {
                        int idx = x + y * w;
                        if (D > z[idx]) {
                            z[idx] = D;
                            double N = 8.0 * (((g * a - G * h * e) * d) - G * h * a - g * e - H * h * c);
                            int si = (int) (N > 0 ? N : 0);
                            if (si >= shades.length) si = shades.length - 1;
                            buf[idx] = shades[si];
                        }
                    }
                    double f = H; H -= stepTheta * G; G += stepTheta * f;
                    f = (3.0 - H * H - G * G) * 0.5; H *= f; G *= f;
                }
                double f = h; h -= stepPhi * g; g += stepPhi * f;
                f = (3.0 - h * h - g * g) * 0.5; h *= f; g *= f;
            }
            StringBuilder sb = new StringBuilder((w + 1) * hgt);
            sb.append("\u001b[H");
            for (int r = 0; r < hgt; r++) {
                sb.append(buf, r * w, w).append('\n');
            }
            System.out.print(sb);
            System.out.flush();
            double f = e; e -= stepA * a; a += stepA * f;
            f = (3.0 - e * e - a * a) * 0.5; e *= f; a *= f;
            f = c; c -= stepB * d; d += stepB * f;
            f = (3.0 - c * c - d * d) * 0.5; c *= f; d *= f;
        }
    }
}
