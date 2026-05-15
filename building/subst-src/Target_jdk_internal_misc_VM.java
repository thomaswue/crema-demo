import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;

@TargetClass(className = "jdk.internal.misc.VM")
public final class Target_jdk_internal_misc_VM {
    @Substitute
    public static String[] getRuntimeArguments() {
        return new String[0];
    }

    @Substitute
    static void initialize() {
    }
}
