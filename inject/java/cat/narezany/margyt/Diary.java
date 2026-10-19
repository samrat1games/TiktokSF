package cat.narezany.margyt;

import java.util.ArrayList;
import java.util.List;

/**
 * What used to be the mod's written diary. Nothing is kept or shown any more;
 * the calls stay because a hundred places say what they are doing, and each of
 * them costs nothing here.
 */
public final class Diary {

    private Diary() {}

    public static void note(String line) {}

    public static void detail(String line) {}

    public static List<String> lines() {
        return new ArrayList<String>();
    }

    public static void clear() {}
}
