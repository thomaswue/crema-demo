public class Sieve {
    static int sieve(int n) {
        boolean[] mark = new boolean[n];
        for (int i = 3; i * i < n; i += 2) {
            for (int j = i * i; j < n; j += 2 * i) {
                mark[j] = true;
            }
        }
        int cnt = 0;
        if (n > 2) {
            ++cnt;
        }
        for (int i = 3; i < n; i += 2) {
            if (!mark[i]) {
                cnt++;
            }
        }
        return cnt;
    }

    public static void main(String[] args) {
        long startNanos = System.nanoTime();
        int cnt = sieve(10_000_000);
        long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000;
        System.out.println(cnt);
        System.out.println(elapsedMillis);
    }
}
