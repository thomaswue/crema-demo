public class BF {

    static String bf(String code, String input) {
        int n = code.length();
        int[] jump = new int[n];
        for (int i = 0; i < n; i++) jump[i] = -1;
        int[] stack = new int[n]; int sp = 0;
        for (int i = 0; i < n; i++) {
            char c = code.charAt(i);
            if (c == '[') stack[sp++] = i;
            else if (c == ']') { int j = stack[--sp]; jump[i] = j; jump[j] = i; }
        }
        byte[] tape = new byte[30000];
        int p = 0, pc = 0, in = 0;
        StringBuilder out = new StringBuilder();
        while (pc < n) {
            switch (code.charAt(pc)) {
                case '>': p++; break;
                case '<': p--; break;
                case '+': tape[p]++; break;
                case '-': tape[p]--; break;
                case '.': out.append((char)(tape[p])); break;
                case ',': tape[p] = (input != null && in < input.length()) ? ((byte) input.charAt(in++)) : 0; break;
                case '[': if (tape[p] == 0) pc = jump[pc]; break;
                case ']': if (tape[p] != 0) pc = jump[pc]; break;
            }
            pc++;
        }
        return out.toString();
    }


    public void main(String[] args) {
        String hello = "++++++++++[>+++++++>++++++++++>+++++++++++>+++++++++++>++++>+++>+++++++>+++++++++++>++++++++++>+++++++++++>++++++++++>+++<<<<<<<<<<<<-]>++.>+.>--..>+.>++++.>++.>---.>++++.>+.>-.>---.>+++.";
        System.out.println(bf(hello, ""));
    }

}
