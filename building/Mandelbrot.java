public class Mandelbrot {
    public static void main(String[] args) {
        int W = 120, H = 60, M = 50;String sh = " .:-=+*#%@";
        for (int y=0;y<H;y++) {
            double ci= (y-H/2.0)/(H/4.0);
            StringBuilder sb = new StringBuilder();
            for(int x=0;x<W;x++){
                double cr = (x-W/2.0)/(W/3.5),zr=0,zi=0; int i=0;
                while (zr*zr+zi*zi<4 && i<M) {
                    double nzr = zr*zr-zi*zi+cr;
                    zi = 2*zr*zi+ci;
                    zr = nzr;
                    i++;
                }
                sb.append(sh.charAt((i==M)?sh.length()-1:(i*sh.length()/M)));
            }
            System.out.println(sb);
        }
    }
}
