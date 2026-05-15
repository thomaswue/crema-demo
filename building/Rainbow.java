public class Rainbow {

    public static void main(String[] args) throws Exception {

        int W = 80, H = 24;
        String esc="\u001b[", reset=new StringBuilder(esc).append("0m").toString();

        for (int t = 0; t < 200; t++) { // run for 200 frames
            StringBuilder frame = new StringBuilder();
            frame.append(esc).append("H"); // move cursor to top-left
            for (int y = 0; y < H; y++) {
                for (int x = 0; x < W; x++) {
                    double v = Math.sin(x/8.0 + t*0.1) +
                                Math.sin(y/6.0 + t*0.15) +
                                Math.sin((x+y)/7.0 + t*0.05);
                    int r = (int)(127 + 128 * Math.sin(v));
                    int g = (int)(127 + 128 * Math.sin(v + 2));
                    int b = (int)(127 + 128 * Math.sin(v + 4));
                    frame.append(esc).append("48;2;")
                            .append(r).append(";")
                            .append(g).append(";")
                            .append(b).append("m ").append(reset);
                }
                frame.append("\n");
            }
            System.out.print(frame);
            Thread.sleep(20);
        }
    }
}
