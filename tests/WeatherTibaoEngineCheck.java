package dev.weather.oneplus;

import android.content.Context;
import java.io.File;
import java.io.IOException;
import java.nio.LongBuffer;
import java.util.Arrays;
import java.util.concurrent.CancellationException;

/** Host checks for the EOS boundary and preflight paths that must not load native models. */
public final class WeatherTibaoEngineCheck {
    private interface Action { void run() throws Exception; }
    private static int checked;
    private static void expect(Class<? extends Throwable> kind, Action action) throws Exception {
        try { action.run(); } catch (Throwable failure) {
            if (kind.isInstance(failure)) { checked++; return; }
            throw new AssertionError("Expected " + kind.getName(), failure);
        }
        throw new AssertionError("Expected " + kind.getName());
    }
    private static void tokens(long[] raw, int prompt, long... expected) throws Exception {
        LongBuffer buffer = LongBuffer.wrap(raw);
        int originalPosition = buffer.position();
        if (!Arrays.equals(WeatherTibaoEngine.trimSemantic(buffer, prompt), expected)
                || buffer.position() != originalPosition)
            throw new AssertionError("Semantic prompt/EOS boundary changed");
        checked++;
    }
    public static void main(String[] args) throws Exception {
        // Sampled EOS and greedy EOS both discard the final appended token.
        tokens(new long[]{1901, 1902, 8, 9, 1024}, 2, 8, 9);
        tokens(new long[]{1901, 1902, 8, 9, 37}, 2, 8, 9);
        tokens(new long[]{1901, 0, 1024}, 1, 0);
        expect(IOException.class, () -> WeatherTibaoEngine.trimSemantic(LongBuffer.wrap(new long[]{7, 1024}), 1));
        expect(IOException.class, () -> WeatherTibaoEngine.trimSemantic(LongBuffer.wrap(new long[]{7, 8, 1024, 1024}), 1));
        expect(IOException.class, () -> WeatherTibaoEngine.trimSemantic(LongBuffer.wrap(new long[]{7, -1, 1024}), 1));
        expect(IOException.class, () -> WeatherTibaoEngine.trimSemantic(LongBuffer.wrap(new long[]{7, 8, 1024}), -1));
        long[] maximum = new long[502]; maximum[501] = 1024;
        if (WeatherTibaoEngine.trimSemantic(LongBuffer.wrap(maximum), 1).length != 500) throw new AssertionError("500 token boundary");
        checked++;
        expect(IOException.class, () -> WeatherTibaoEngine.trimSemantic(LongBuffer.wrap(new long[503]), 1));
        WeatherTibaoEngine.validateText("今天上海晴，气温二十三度。");
        WeatherTibaoEngine.validateText(new String(new char[240]).replace('\0', '晴'));
        checked += 2;
        expect(IllegalArgumentException.class, () -> WeatherTibaoEngine.validateText(" "));
        expect(IllegalArgumentException.class, () -> WeatherTibaoEngine.validateText(new String(new char[241]).replace('\0', '晴')));
        expect(IllegalArgumentException.class, () -> WeatherTibaoEngine.validateText("\ud800"));
        expect(IllegalArgumentException.class, () -> WeatherTibaoEngine.validateText("天气\u0000"));
        WeatherTibaoEngine engine = new WeatherTibaoEngine(new Context(), new File("missing-models"));
        expect(CancellationException.class, () -> engine.synthesize("今天晴。", 13, () -> true));
        expect(IllegalArgumentException.class, () -> engine.synthesize("今天晴。", 0, () -> false));
        Thread.currentThread().interrupt();
        expect(CancellationException.class, () -> engine.synthesize("今天晴。", 13, () -> false));
        Thread.interrupted();
        engine.close();
        expect(IllegalStateException.class, () -> engine.synthesize("今天晴。", 13, () -> false));
        engine.close();
        System.out.println("WeatherTibaoEngineCheck: " + checked + " checks passed");
    }
}
