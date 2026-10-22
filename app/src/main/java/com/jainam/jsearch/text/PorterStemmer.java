package com.jainam.jsearch.text;

/**
 * The Porter stemming algorithm (M. F. Porter, 1980), implemented from the paper.
 *
 * <p>Stemming maps related word forms to one index term, so a search for "connection" also
 * finds "connected", "connecting" and "connections" (all become "connect"). The stem does not
 * have to be a real word ("generalization" becomes "gener"); it only has to be consistent.
 *
 * <p>The algorithm strips suffixes in five steps. Most rules only apply when the remaining stem
 * is "long enough", measured by m: the number of vowel-consonant sequences. For example
 * "tree" has m = 0, "trouble" m = 1, "oaten" m = 2.
 */
public final class PorterStemmer {
    private char[] b;
    private int k;  // end of the current word (inclusive)
    private int j;  // end of the stem being tested

    public static String stem(String word) {
        if (word.length() <= 2) {
            return word; // the algorithm leaves very short words alone
        }
        PorterStemmer s = new PorterStemmer();
        s.b = word.toCharArray();
        s.k = s.b.length - 1;
        s.step1ab();
        if (s.k > 0) {
            s.step1c();
            s.step2();
            s.step3();
            s.step4();
            s.step5();
        }
        return new String(s.b, 0, s.k + 1);
    }

    /** True if b[i] is a consonant. 'y' is a consonant at the start or after a vowel. */
    private boolean cons(int i) {
        switch (b[i]) {
            case 'a', 'e', 'i', 'o', 'u':
                return false;
            case 'y':
                return i == 0 || !cons(i - 1);
            default:
                return true;
        }
    }

    /** m(): the number of consonant-vowel sequences in b[0..j]. */
    private int m() {
        int n = 0;
        int i = 0;
        while (true) {
            if (i > j) {
                return n;
            }
            if (!cons(i)) {
                break;
            }
            i++;
        }
        i++;
        while (true) {
            while (true) {
                if (i > j) {
                    return n;
                }
                if (cons(i)) {
                    break;
                }
                i++;
            }
            i++;
            n++;
            while (true) {
                if (i > j) {
                    return n;
                }
                if (!cons(i)) {
                    break;
                }
                i++;
            }
            i++;
        }
    }

    /** True if b[0..j] contains a vowel. */
    private boolean vowelInStem() {
        for (int i = 0; i <= j; i++) {
            if (!cons(i)) {
                return true;
            }
        }
        return false;
    }

    /** True if b[i-1..i] is a double consonant. */
    private boolean doublec(int i) {
        return i >= 1 && b[i] == b[i - 1] && cons(i);
    }

    /** True if b[i-2..i] is consonant-vowel-consonant and the last is not w, x or y (e.g. "hop"). */
    private boolean cvc(int i) {
        if (i < 2 || !cons(i) || cons(i - 1) || !cons(i - 2)) {
            return false;
        }
        char ch = b[i];
        return ch != 'w' && ch != 'x' && ch != 'y';
    }

    private boolean ends(String s) {
        int l = s.length();
        int o = k - l + 1;
        if (o < 0) {
            return false;
        }
        for (int i = 0; i < l; i++) {
            if (b[o + i] != s.charAt(i)) {
                return false;
            }
        }
        j = k - l;
        return true;
    }

    /** Replaces b[j+1..k] with s. */
    private void setto(String s) {
        int l = s.length();
        int o = j + 1;
        if (o + l > b.length) {
            char[] nb = new char[o + l];
            System.arraycopy(b, 0, nb, 0, b.length);
            b = nb;
        }
        for (int i = 0; i < l; i++) {
            b[o + i] = s.charAt(i);
        }
        k = j + l;
    }

    private void r(String s) {
        if (m() > 0) {
            setto(s);
        }
    }

    /** Step 1ab: plurals and -ed / -ing.  caresses → caress, ponies → poni, agreed → agree, hopping → hop. */
    private void step1ab() {
        if (b[k] == 's') {
            if (ends("sses")) {
                k -= 2;
            } else if (ends("ies")) {
                setto("i");
            } else if (b[k - 1] != 's') {
                k--;
            }
        }
        if (ends("eed")) {
            if (m() > 0) {
                k--;
            }
        } else if ((ends("ed") || ends("ing")) && vowelInStem()) {
            k = j;
            if (ends("at")) {
                setto("ate");
            } else if (ends("bl")) {
                setto("ble");
            } else if (ends("iz")) {
                setto("ize");
            } else if (doublec(k)) {
                k--;
                char ch = b[k];
                if (ch == 'l' || ch == 's' || ch == 'z') {
                    k++;
                }
            } else if (m() == 1 && cvc(k)) {
                setto("e");
            }
        }
    }

    /** Step 1c: terminal y → i when there is another vowel.  happy → happi. */
    private void step1c() {
        if (ends("y") && vowelInStem()) {
            b[k] = 'i';
        }
    }

    /** Step 2: double suffixes to single ones.  relational → relate, digitizer → digitize. */
    private void step2() {
        if (k == 0) {
            return;
        }
        switch (b[k - 1]) {
            case 'a' -> {
                if (ends("ational")) r("ate");
                else if (ends("tional")) r("tion");
            }
            case 'c' -> {
                if (ends("enci")) r("ence");
                else if (ends("anci")) r("ance");
            }
            case 'e' -> {
                if (ends("izer")) r("ize");
            }
            case 'l' -> {
                if (ends("bli")) r("ble");
                else if (ends("alli")) r("al");
                else if (ends("entli")) r("ent");
                else if (ends("eli")) r("e");
                else if (ends("ousli")) r("ous");
            }
            case 'o' -> {
                if (ends("ization")) r("ize");
                else if (ends("ation")) r("ate");
                else if (ends("ator")) r("ate");
            }
            case 's' -> {
                if (ends("alism")) r("al");
                else if (ends("iveness")) r("ive");
                else if (ends("fulness")) r("ful");
                else if (ends("ousness")) r("ous");
            }
            case 't' -> {
                if (ends("aliti")) r("al");
                else if (ends("iviti")) r("ive");
                else if (ends("biliti")) r("ble");
            }
            case 'g' -> {
                if (ends("logi")) r("log");
            }
            default -> { }
        }
    }

    /** Step 3: -ic-, -full, -ness etc.  electrical → electric, hopeful → hope. */
    private void step3() {
        switch (b[k]) {
            case 'e' -> {
                if (ends("icate")) r("ic");
                else if (ends("ative")) r("");
                else if (ends("alize")) r("al");
            }
            case 'i' -> {
                if (ends("iciti")) r("ic");
            }
            case 'l' -> {
                if (ends("ical")) r("ic");
                else if (ends("ful")) r("");
            }
            case 's' -> {
                if (ends("ness")) r("");
            }
            default -> { }
        }
    }

    /** Step 4: remove -ant, -ence etc. when m > 1.  revival → reviv, adjustment → adjust. */
    private void step4() {
        if (k == 0) {
            return;
        }
        switch (b[k - 1]) {
            case 'a':
                if (ends("al")) break;
                return;
            case 'c':
                if (ends("ance") || ends("ence")) break;
                return;
            case 'e':
                if (ends("er")) break;
                return;
            case 'i':
                if (ends("ic")) break;
                return;
            case 'l':
                if (ends("able") || ends("ible")) break;
                return;
            case 'n':
                if (ends("ant") || ends("ement") || ends("ment") || ends("ent")) break;
                return;
            case 'o':
                if (ends("ion") && j >= 0 && (b[j] == 's' || b[j] == 't')) break;
                if (ends("ou")) break;
                return;
            case 's':
                if (ends("ism")) break;
                return;
            case 't':
                if (ends("ate") || ends("iti")) break;
                return;
            case 'u':
                if (ends("ous")) break;
                return;
            case 'v':
                if (ends("ive")) break;
                return;
            case 'z':
                if (ends("ize")) break;
                return;
            default:
                return;
        }
        if (m() > 1) {
            k = j;
        }
    }

    /** Step 5: remove a final -e and reduce -ll when m > 1.  probate → probat, controll → control. */
    private void step5() {
        j = k;
        if (b[k] == 'e') {
            int a = m();
            if (a > 1 || (a == 1 && !cvc(k - 1))) {
                k--;
            }
        }
        if (b[k] == 'l' && doublec(k) && m() > 1) {
            k--;
        }
    }
}
