package dev.weather.oneplus;

import android.content.Context;
import ai.onnxruntime.OnnxJavaType;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import org.json.JSONArray;
import org.json.JSONObject;

/** CPU GPT-SoVITS V2ProPlus inference; models remain in private optional storage. */
public final class WeatherTibaoEngine implements AutoCloseable {
    public static final int SAMPLE_RATE = 32000;
    public static final int MAX_TEXT_CODEPOINTS = 240;
    public static final int MAX_PHONES = 768;
    private static final int MAX_STAGE_CALLS = 500;
    private static final int LAYERS = 24;
    private static final String[] REFERENCE_NAMES = {"ref_seq", "ref_bert", "ssl_content", "ge", "ge_advanced"};
    private final Context context;
    private final File directory;
    private OrtEnvironment environment;
    private OrtSession bert, encoder, first, stage, vocoder;
    private WeatherTibaoFrontend frontend;
    private int vocoderPreset;
    private boolean closed;
    private final Map<String, OwnedTensor> reference = new LinkedHashMap<>();

    /** Construction performs no native/model initialization and is safe for service startup. */
    public WeatherTibaoEngine(Context context, File modelDirectory) {
        if (context == null || modelDirectory == null) throw new IllegalArgumentException("Missing speech context or models");
        this.context = context.getApplicationContext();
        this.directory = modelDirectory;
    }

    public static final class Audio {
        public final float[] samples;
        public final int sampleRate = SAMPLE_RATE;
        public final String normalizedText;
        public final long initializationMillis, frontendMillis, semanticMillis, vocoderMillis;
        public final int promptTokens, semanticTokens, stageCalls;
        private Audio(float[] samples, String text, long load, long frontendTime, long semanticTime,
                      long vocoderTime, Semantic semantic) {
            this.samples = samples;
            normalizedText = text;
            initializationMillis = load;
            frontendMillis = frontendTime;
            semanticMillis = semanticTime;
            vocoderMillis = vocoderTime;
            promptTokens = semantic.promptTokens;
            semanticTokens = semantic.tokens.length;
            stageCalls = semantic.stageCalls;
        }
    }

    /** Returns only after the entire supplied sentence has generated valid PCM. Presets are 13/19/20. */
    public synchronized Audio synthesize(String text, int preset, BooleanSupplier cancelled) throws Exception {
        if (closed) throw new IllegalStateException("Speech engine is closed");
        validateText(text);
        validatePreset(preset);
        checkCancelled(cancelled);
        long before = System.nanoTime();
        initialize(cancelled);
        selectVocoder(preset, cancelled);
        long loadMillis = elapsed(before);
        before = System.nanoTime();
        WeatherTibaoFrontend.Result prepared = frontend.prepare(text);
        checkCancelled(cancelled);
        if (prepared.phones.length == 0 || prepared.phones.length > MAX_PHONES
                || prepared.bert.length != prepared.phones.length * 1024)
            throw new IllegalArgumentException("Speech sentence has too many phonemes or invalid BERT features");
        long frontendMillis = elapsed(before);
        try (OwnedTensor textSeq = longs(prepared.phones, new long[]{1, prepared.phones.length});
             OwnedTensor textBert = floats(prepared.bert, new long[]{prepared.phones.length, 1024})) {
            before = System.nanoTime();
            Semantic semantic = generateSemantic(textSeq.tensor, textBert.tensor, cancelled);
            long semanticMillis = elapsed(before);
            before = System.nanoTime();
            float[] pcm;
            try (OwnedTensor tokens = longs(semantic.tokens, new long[]{1, 1, semantic.tokens.length})) {
                Map<String, OnnxTensor> feed = new LinkedHashMap<>();
                feed.put("text_seq", textSeq.tensor);
                feed.put("pred_semantic", tokens.tensor);
                feed.put("ge", reference.get("ge").tensor);
                feed.put("ge_advanced", reference.get("ge_advanced").tensor);
                checkCancelled(cancelled);
                try (OrtSession.Result result = vocoder.run(feed)) {
                    checkCancelled(cancelled);
                    OnnxTensor audio = tensor(result, "audio");
                    FloatBuffer values = audio.getFloatBuffer().duplicate();
                    if (values.remaining() == 0 || values.remaining() > SAMPLE_RATE * 40)
                        throw new IOException("Speech model returned empty or excessive audio");
                    pcm = new float[values.remaining()];
                    values.get(pcm);
                    for (float value : pcm) if (!Float.isFinite(value)) throw new IOException("Speech model returned nonfinite audio");
                }
            }
            checkCancelled(cancelled);
            return new Audio(pcm, prepared.normalizedText, loadMillis, frontendMillis, semanticMillis,
                    elapsed(before), semantic);
        }
    }

    private void initialize(BooleanSupplier cancelled) throws Exception {
        if (stage != null) return;
        try {
            checkCancelled(cancelled);
            environment = OrtEnvironment.getEnvironment();
            loadReference(cancelled);
            checkCancelled(cancelled);
            bert = open("bert.onnx");
            checkCancelled(cancelled);
            frontend = new WeatherTibaoFrontend(context, environment, bert);
            checkCancelled(cancelled);
            encoder = open("t2s_encoder.onnx");
            requireInput(encoder, "ref_seq", OnnxJavaType.INT64, 1, -1);
            requireInput(encoder, "text_seq", OnnxJavaType.INT64, 1, -1);
            requireInput(encoder, "ref_bert", OnnxJavaType.FLOAT, -1, 1024);
            requireInput(encoder, "text_bert", OnnxJavaType.FLOAT, -1, 1024);
            requireInput(encoder, "ssl_content", OnnxJavaType.FLOAT, 1, 768, -1);
            checkCancelled(cancelled);
            first = open("t2s_first.onnx");
            requireInput(first, "x", OnnxJavaType.FLOAT, 1, -1, 512);
            requireInput(first, "prompts", OnnxJavaType.INT64, 1, -1);
            requireInput(first, "eos_allowed", OnnxJavaType.BOOL);
            checkCancelled(cancelled);
            stage = open("t2s_stage.onnx");
            requireInput(stage, "iy", OnnxJavaType.INT64, 1, -1);
            requireInput(stage, "iy_emb", OnnxJavaType.FLOAT, 1, -1, 512);
            requireInput(stage, "eos_allowed", OnnxJavaType.BOOL);
            for (int layer = 0; layer < LAYERS; layer++) {
                requireInput(stage, "past_k_layer_" + layer, OnnxJavaType.FLOAT, -1, 1, 512);
                requireInput(stage, "past_v_layer_" + layer, OnnxJavaType.FLOAT, -1, 1, 512);
            }
            checkCancelled(cancelled);
        } catch (Exception | LinkageError failure) {
            release();
            throw failure;
        }
    }

    private OrtSession open(String name) throws Exception {
        File graph = child(name);
        if (!graph.isFile() || graph.length() == 0) throw new IOException("Speech model is missing: " + name);
        try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
            options.setIntraOpNumThreads(2);
            options.setInterOpNumThreads(1);
            options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL);
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
            options.setCPUArenaAllocator(false);
            options.setMemoryPatternOptimization(false);
            options.addConfigEntry("session.intra_op.allow_spinning", "0");
            options.addConfigEntry("session.inter_op.allow_spinning", "0");
            // CPU is the default provider. Graph paths preserve external-data weight resolution.
            return environment.createSession(graph.getAbsolutePath(), options);
        }
    }

    private void selectVocoder(int preset, BooleanSupplier cancelled) throws Exception {
        if (vocoder != null && vocoderPreset == preset) return;
        checkCancelled(cancelled);
        if (vocoder != null) { vocoder.close(); vocoder = null; }
        OrtSession candidate = open("vits_" + preset + ".onnx");
        try {
            requireInput(candidate, "text_seq", OnnxJavaType.INT64, 1, -1);
            requireInput(candidate, "pred_semantic", OnnxJavaType.INT64, 1, 1, -1);
            requireInput(candidate, "ge", OnnxJavaType.FLOAT, 1, 1024, 1);
            requireInput(candidate, "ge_advanced", OnnxJavaType.FLOAT, 1, 512, 1);
            checkCancelled(cancelled);
            vocoder = candidate;
            vocoderPreset = preset;
        } catch (Exception failure) {
            candidate.close();
            throw failure;
        }
    }

    private Semantic generateSemantic(OnnxTensor textSeq, OnnxTensor textBert, BooleanSupplier cancelled) throws Exception {
        Map<String, OnnxTensor> encoderFeed = new LinkedHashMap<>();
        for (String name : new String[]{"ref_seq", "ref_bert", "ssl_content"}) encoderFeed.put(name, reference.get(name).tensor);
        encoderFeed.put("text_seq", textSeq);
        encoderFeed.put("text_bert", textBert);
        OrtSession.Result current = null;
        int promptLength;
        try {
            checkCancelled(cancelled);
            try (OrtSession.Result encoded = encoder.run(encoderFeed);
                 OnnxTensor eosBlocked = OnnxTensor.createTensor(environment, Boolean.FALSE)) {
                checkCancelled(cancelled);
                OnnxTensor prompts = tensor(encoded, "prompts");
                long[] promptShape = prompts.getInfo().getShape();
                if (promptShape.length != 2 || promptShape[0] != 1 || promptShape[1] <= 0 || promptShape[1] > 2048)
                    throw new IOException("Speech encoder returned invalid prompt shape");
                promptLength = (int) promptShape[1];
                Map<String, OnnxTensor> feed = new LinkedHashMap<>();
                feed.put("x", tensor(encoded, "x"));
                feed.put("prompts", prompts);
                feed.put("eos_allowed", eosBlocked);
                current = first.run(feed);
            }
            checkCancelled(cancelled);
            try (OnnxTensor eosBlocked = OnnxTensor.createTensor(environment, Boolean.FALSE);
                 OnnxTensor eosAllowed = OnnxTensor.createTensor(environment, Boolean.TRUE)) {
                for (int index = 0; index < MAX_STAGE_CALLS; index++) {
                    checkCancelled(cancelled);
                    Map<String, OnnxTensor> feed = new LinkedHashMap<>();
                    feed.put("iy", tensor(current, "y"));
                    feed.put("iy_emb", tensor(current, "y_emb"));
                    for (int layer = 0; layer < LAYERS; layer++) {
                        feed.put("past_k_layer_" + layer, tensor(current, "present_k_layer_" + layer));
                        feed.put("past_v_layer_" + layer, tensor(current, "present_v_layer_" + layer));
                    }
                    feed.put("eos_allowed", index + 1 >= 11 ? eosAllowed : eosBlocked);
                    // Keep every previous output alive until run has consumed its dynamic KV tensors.
                    OrtSession.Result next = stage.run(feed);
                    OrtSession.Result previous = current;
                    current = next;
                    previous.close();
                    checkCancelled(cancelled);
                    Object stop = tensor(current, "stop_condition_tensor").getValue();
                    if (!(stop instanceof Boolean)) throw new IOException("Speech decoder stop flag is not scalar");
                    if ((Boolean) stop) {
                        OnnxTensor generated = tensor(current, "y");
                        LongBuffer values = generated.getLongBuffer().duplicate();
                        long[] shape = generated.getInfo().getShape();
                        if (shape.length != 2 || shape[0] != 1 || shape[1] != values.remaining())
                            throw new IOException("Speech decoder returned invalid semantic shape");
                        return new Semantic(trimSemantic(values, promptLength), promptLength, index + 1);
                    }
                }
            }
            throw new IOException("Speech decoder reached 500 stages without EOS; refusing truncated speech");
        } finally {
            if (current != null) current.close();
        }
    }

    /** Official naive removes the final appended token for sampled OR greedy EOS. */
    static long[] trimSemantic(LongBuffer generated, int promptLength) throws IOException {
        int count = generated.remaining() - promptLength - 1;
        if (promptLength < 0 || count <= 0 || count > MAX_STAGE_CALLS)
            throw new IOException("Speech decoder returned an empty or excessive semantic sequence");
        LongBuffer values = generated.duplicate();
        values.position(values.position() + promptLength);
        long[] tokens = new long[count];
        values.get(tokens);
        for (long token : tokens) if (token < 0 || token >= 1024)
            throw new IOException("Speech decoder returned EOS inside the semantic sequence");
        return tokens;
    }

    private void loadReference(BooleanSupplier cancelled) throws Exception {
        JSONObject manifest = new JSONObject(new String(read(child("reference.json"), 65536), StandardCharsets.UTF_8));
        JSONArray entries = manifest.getJSONArray("tensors");
        if (entries.length() != REFERENCE_NAMES.length) throw new IOException("Invalid speech reference tensor count");
        for (int index = 0; index < entries.length(); index++) {
            checkCancelled(cancelled);
            JSONObject row = entries.getJSONObject(index);
            String name = row.getString("name");
            if (!Arrays.asList(REFERENCE_NAMES).contains(name) || reference.containsKey(name))
                throw new IOException("Unknown or duplicate speech reference tensor");
            if (!row.getString("file").equals("ref." + name + ".bin")) throw new IOException("Invalid speech reference filename");
            String dtype = row.getString("dtype");
            if (!(name.equals("ref_seq") ? "int64" : "float32").equals(dtype)) throw new IOException("Invalid speech reference dtype");
            JSONArray dimensions = row.getJSONArray("shape");
            long[] shape = new long[dimensions.length()];
            long count = 1;
            for (int dim = 0; dim < shape.length; dim++) {
                shape[dim] = dimensions.getLong(dim);
                if (shape[dim] <= 0 || shape[dim] > 4096) throw new IOException("Invalid speech reference shape");
                count = Math.multiplyExact(count, shape[dim]);
            }
            long expected = Math.multiplyExact(count, name.equals("ref_seq") ? 8 : 4);
            if (expected > 16 * 1024 * 1024 || expected != row.getLong("bytes")) throw new IOException("Invalid speech reference size");
            byte[] bytes = read(child(row.getString("file")), (int) expected);
            if (bytes.length != expected || !hex(MessageDigest.getInstance("SHA-256").digest(bytes)).equals(row.getString("sha256")))
                throw new IOException("Speech reference checksum mismatch");
            ByteBuffer buffer = ByteBuffer.allocateDirect(bytes.length).order(ByteOrder.nativeOrder());
            // Reference files are explicitly little-endian, independently of host byte order.
            ByteBuffer source = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            OnnxTensor tensor;
            if (name.equals("ref_seq")) {
                LongBuffer output = buffer.asLongBuffer();
                while (source.hasRemaining()) output.put(source.getLong());
                output.flip();
                tensor = OnnxTensor.createTensor(environment, output, shape);
            } else {
                FloatBuffer output = buffer.asFloatBuffer();
                while (source.hasRemaining()) {
                    float value = source.getFloat();
                    if (!Float.isFinite(value)) throw new IOException("Nonfinite speech reference feature");
                    output.put(value);
                }
                output.flip();
                tensor = OnnxTensor.createTensor(environment, output, shape);
            }
            reference.put(name, new OwnedTensor(tensor, buffer));
        }
        long referenceLength = reference.get("ref_seq").tensor.getInfo().getShape()[1];
        requireShape(reference.get("ref_seq").tensor, 1, -1);
        requireShape(reference.get("ref_bert").tensor, referenceLength, 1024);
        requireShape(reference.get("ssl_content").tensor, 1, 768, -1);
        requireShape(reference.get("ge").tensor, 1, 1024, 1);
        requireShape(reference.get("ge_advanced").tensor, 1, 512, 1);
    }

    private OwnedTensor longs(long[] values, long[] shape) throws OrtException {
        ByteBuffer bytes = ByteBuffer.allocateDirect(Math.multiplyExact(values.length, 8)).order(ByteOrder.nativeOrder());
        LongBuffer data = bytes.asLongBuffer();
        data.put(values).flip();
        return new OwnedTensor(OnnxTensor.createTensor(environment, data, shape), bytes);
    }

    private OwnedTensor floats(float[] values, long[] shape) throws OrtException, IOException {
        for (float value : values) if (!Float.isFinite(value)) throw new IOException("Nonfinite BERT feature");
        ByteBuffer bytes = ByteBuffer.allocateDirect(Math.multiplyExact(values.length, 4)).order(ByteOrder.nativeOrder());
        FloatBuffer data = bytes.asFloatBuffer();
        data.put(values).flip();
        return new OwnedTensor(OnnxTensor.createTensor(environment, data, shape), bytes);
    }

    static void validateText(String text) {
        if (text == null || text.trim().isEmpty()) throw new IllegalArgumentException("Speech text is empty");
        if (text.codePointCount(0, text.length()) > MAX_TEXT_CODEPOINTS)
            throw new IllegalArgumentException("Speech sentence exceeds 240 characters");
        for (int offset = 0; offset < text.length();) {
            int codepoint = text.codePointAt(offset);
            if ((Character.isISOControl(codepoint) && !Character.isWhitespace(codepoint)) || (codepoint >= 0xd800 && codepoint <= 0xdfff))
                throw new IllegalArgumentException("Speech text contains invalid characters");
            offset += Character.charCount(codepoint);
        }
    }

    private static void validatePreset(int preset) {
        if (preset != 13 && preset != 19 && preset != 20) throw new IllegalArgumentException("Unknown Tibao voice preset");
    }

    private static void checkCancelled(BooleanSupplier cancelled) {
        if (Thread.currentThread().isInterrupted() || (cancelled != null && cancelled.getAsBoolean()))
            throw new CancellationException("Speech synthesis cancelled");
    }

    private static long elapsed(long before) { return (System.nanoTime() - before) / 1000000L; }

    private File child(String name) throws IOException {
        File result = new File(directory, name);
        if (!result.getCanonicalPath().startsWith(directory.getCanonicalPath() + File.separator))
            throw new IOException("Speech model path escapes private storage");
        return result;
    }

    private static byte[] read(File file, int limit) throws IOException {
        long size = file.length();
        if (!file.isFile() || size <= 0 || size > limit) throw new IOException("Missing or oversized speech data: " + file.getName());
        byte[] bytes = new byte[(int) size];
        try (FileInputStream input = new FileInputStream(file)) {
            int offset = 0;
            while (offset < bytes.length) {
                int count = input.read(bytes, offset, bytes.length - offset);
                if (count < 0) throw new IOException("Incomplete speech data");
                offset += count;
            }
            if (input.read() != -1) throw new IOException("Speech data changed while reading");
        }
        return bytes;
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(Character.forDigit((value >>> 4) & 15, 16)).append(Character.forDigit(value & 15, 16));
        return result.toString();
    }

    private static OnnxTensor tensor(OrtSession.Result result, String name) throws IOException {
        OnnxValue value = result.get(name).orElseThrow(() -> new IOException("Missing speech graph output: " + name));
        if (!(value instanceof OnnxTensor)) throw new IOException("Invalid speech graph output: " + name);
        return (OnnxTensor) value;
    }

    private static void requireInput(OrtSession session, String name, OnnxJavaType type, long... shape) throws Exception {
        if (!session.getInputInfo().containsKey(name) || !(session.getInputInfo().get(name).getInfo() instanceof TensorInfo))
            throw new IOException("Missing speech graph input: " + name);
        TensorInfo info = (TensorInfo) session.getInputInfo().get(name).getInfo();
        if (info.type != type) throw new IOException("Wrong speech graph input type: " + name);
        long[] actual = info.getShape();
        if (actual.length != shape.length) throw new IOException("Wrong speech graph input rank: " + name);
        for (int dim = 0; dim < shape.length; dim++)
            if (shape[dim] >= 0 && actual[dim] != shape[dim]) throw new IOException("Wrong speech graph input shape: " + name);
    }

    private static void requireShape(OnnxTensor tensor, long... expected) throws IOException {
        long[] actual = tensor.getInfo().getShape();
        if (actual.length != expected.length) throw new IOException("Wrong speech reference rank");
        for (int dim = 0; dim < actual.length; dim++)
            if (expected[dim] >= 0 && actual[dim] != expected[dim]) throw new IOException("Wrong speech reference shape");
    }

    private static final class Semantic {
        final long[] tokens;
        final int promptTokens, stageCalls;
        Semantic(long[] tokens, int promptTokens, int stageCalls) {
            this.tokens = tokens; this.promptTokens = promptTokens; this.stageCalls = stageCalls;
        }
    }

    private static final class OwnedTensor implements AutoCloseable {
        final OnnxTensor tensor;
        // The direct buffer must outlive the native tensor; dropping it follows tensor.close().
        private ByteBuffer buffer;
        OwnedTensor(OnnxTensor tensor, ByteBuffer buffer) { this.tensor = tensor; this.buffer = buffer; }
        @Override public void close() { tensor.close(); buffer = null; }
    }

    private void release() {
        List<AutoCloseable> resources = new ArrayList<>();
        if (frontend != null) resources.add(frontend);
        for (OrtSession session : new OrtSession[]{vocoder, stage, first, encoder, bert}) if (session != null) resources.add(session);
        resources.addAll(reference.values());
        for (AutoCloseable resource : resources) try { resource.close(); } catch (Exception ignored) { }
        reference.clear();
        frontend = null;
        vocoder = stage = first = encoder = bert = null;
        // OrtEnvironment is the process singleton; Java ORT defines close() as a no-op.
        if (environment != null) environment.close();
        environment = null;
    }

    @Override public synchronized void close() { closed = true; release(); }
}
