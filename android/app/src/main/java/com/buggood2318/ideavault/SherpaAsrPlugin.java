package com.buggood2318.ideavault;

import android.Manifest;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.OnlineModelConfig;
import com.k2fsa.sherpa.onnx.OnlineRecognizer;
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OnlineRecognizerResult;
import com.k2fsa.sherpa.onnx.OnlineStream;
import com.k2fsa.sherpa.onnx.OnlineZipformer2CtcModelConfig;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * 端侧离线语音识别（sherpa-onnx + zipformer2-ctc 中文 int8 模型）
 *
 * 为什么要它：手机系统语音识别在部分 ROM 上不可用/不准，且要联网；
 * 这个插件把 26MB 模型打进 APK，完全离线、边说边出字。
 *
 * 前端约定（Capacitor）：
 *   await SherpaAsr.init()          -> { ready:true }
 *   await SherpaAsr.start()         -> 开始听（首次会申请录音权限）
 *   await SherpaAsr.stop()          -> 结束
 *   await SherpaAsr.feedWav()       -> 调试用：不靠麦克风，直接喂 assets 里的 probe.wav
 *   事件：partial {text} / final {text}
 */
@CapacitorPlugin(name = "SherpaAsr", permissions = {
        @Permission(alias = "mic", strings = { Manifest.permission.RECORD_AUDIO })
})
public class SherpaAsrPlugin extends Plugin {

    private static final String TAG = "SherpaAsr";
    private static final int SAMPLE_RATE = 16000;
    private static final String MODEL_ASSET = "asr/model.int8.onnx";
    private static final String TOKENS_ASSET = "asr/tokens.txt";
    private static final String PROBE_ASSET = "asr/probe.wav";

    private OnlineRecognizer recognizer = null;
    private volatile boolean running = false;
    private Thread worker = null;
    private AudioRecord recorder = null;
    private String lastText = "";

    // ---------------- 模型加载 ----------------

    private File assetToFile(String assetPath) throws IOException {
        File dir = new File(getContext().getFilesDir(), "asr");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("无法创建目录 " + dir);
        }
        File out = new File(dir, new File(assetPath).getName());
        // 已经拷过就复用（文件大小一致即认为完整）
        try (InputStream is = getContext().getAssets().open(assetPath)) {
            long avail = is.available();
            if (out.exists() && out.length() == avail) return out;
        } catch (IOException ignored) {
        }
        try (InputStream is = getContext().getAssets().open(assetPath);
             FileOutputStream os = new FileOutputStream(out)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
            os.flush();
        }
        return out;
    }

    private synchronized OnlineRecognizer ensureRecognizer() throws IOException {
        if (recognizer != null) return recognizer;
        File model = assetToFile(MODEL_ASSET);
        File tokens = assetToFile(TOKENS_ASSET);
        OnlineRecognizerConfig config = OnlineRecognizerConfig.builder()
                .setOnlineModelConfig(OnlineModelConfig.builder()
                        .setZipformer2Ctc(OnlineZipformer2CtcModelConfig.builder()
                                .setModel(model.getAbsolutePath())
                                .build())
                        .setTokens(tokens.getAbsolutePath())
                        .setNumThreads(2)
                        .setDebug(false)
                        .build())
                .setFeatureConfig(FeatureConfig.builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setFeatureDim(80)
                        .build())
                .setDecodingMethod("greedy_search")
                .setEnableEndpoint(false)
                .build();
        recognizer = new OnlineRecognizer(config);
        return recognizer;
    }

    private void emit(final String event, final String text) {
        JSObject data = new JSObject();
        data.put("text", text);
        if (getActivity() != null) {
            getActivity().runOnUiThread(() -> notifyListeners(event, data));
        } else {
            notifyListeners(event, data);
        }
    }

    @PluginMethod
    public void isReady(PluginCall call) {
        JSObject ret = new JSObject();
        ret.put("ready", recognizer != null);
        ret.put("modelLoaded", recognizer != null);
        call.resolve(ret);
    }

    @PluginMethod
    public void init(PluginCall call) {
        if (recognizer != null) {
            JSObject ret = new JSObject();
            ret.put("ready", true);
            call.resolve(ret);
            return;
        }
        new Thread(() -> {
            try {
                long t0 = System.currentTimeMillis();
                ensureRecognizer();
                JSObject ret = new JSObject();
                ret.put("ready", true);
                ret.put("loadMs", System.currentTimeMillis() - t0);
                call.resolve(ret);
            } catch (Throwable e) {
                Log.e(TAG, "加载模型失败", e);
                call.reject("加载离线语音模型失败: " + e.getMessage());
            }
        }, "sherpa-init").start();
    }

    // ---------------- 麦克风实时识别 ----------------

    @PluginMethod
    public void start(PluginCall call) {
        if (getPermissionState("mic") != PermissionState.GRANTED) {
            requestPermissionForAlias("mic", call, "micPermsCallback");
            return;
        }
        startInternal(call);
    }

    @PermissionCallback
    private void micPermsCallback(PluginCall call) {
        if (getPermissionState("mic") == PermissionState.GRANTED) {
            startInternal(call);
        } else {
            call.reject("没有录音权限");
        }
    }

    private void startInternal(PluginCall call) {
        if (running) {
            call.reject("已经在识别中");
            return;
        }
        running = true;
        lastText = "";
        call.resolve();   // 立即返回，结果用事件推

        worker = new Thread(() -> {
            try {
                OnlineRecognizer rec = ensureRecognizer();
                int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                if (minBuf <= 0) minBuf = SAMPLE_RATE;   // 兜底
                recorder = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,
                        SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT, minBuf * 4);
                if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
                    running = false;
                    emit("error", "麦克风打不开");
                    return;
                }
                OnlineStream stream = rec.createStream();
                recorder.startRecording();
                short[] buf = new short[1600];   // 100ms
                while (running) {
                    int n = recorder.read(buf, 0, buf.length);
                    if (n <= 0) continue;
                    float[] samples = new float[n];
                    for (int i = 0; i < n; i++) samples[i] = buf[i] / 32768.0f;
                    stream.acceptWaveform(samples, SAMPLE_RATE);
                    while (rec.isReady(stream)) rec.decode(stream);
                    OnlineRecognizerResult r = rec.getResult(stream);
                    String text = r != null ? r.getText() : "";
                    if (text != null && text.length() > 0 && !text.equals(lastText)) {
                        lastText = text;
                        emit("partial", text);
                    }
                }
                try { stream.inputFinished(); } catch (Throwable ignored) { }
                if (lastText.length() > 0) emit("final", lastText);
            } catch (Throwable e) {
                Log.e(TAG, "识别线程异常", e);
                emit("error", String.valueOf(e.getMessage()));
            } finally {
                running = false;
                try {
                    if (recorder != null) {
                        recorder.stop();
                        recorder.release();
                    }
                } catch (Throwable ignored) {
                }
                recorder = null;
            }
        }, "sherpa-mic");
        worker.start();
    }

    @PluginMethod
    public void stop(PluginCall call) {
        running = false;
        if (worker != null) {
            try { worker.join(1500); } catch (InterruptedException ignored) { }
            worker = null;
        }
        JSObject ret = new JSObject();
        ret.put("text", lastText);
        call.resolve(ret);
    }

    // ---------------- 调试：不靠麦克风喂一段 WAV ----------------

    @PluginMethod
    public void feedWav(PluginCall call) {
        final String asset = call.getString("asset", PROBE_ASSET);
        new Thread(() -> {
            try {
                OnlineRecognizer rec = ensureRecognizer();
                float[] samples;
                int sr;
                try (InputStream is = getContext().getAssets().open(asset)) {
                    byte[] all = readAll(is);
                    Wav w = parseWav(all);
                    samples = w.samples;
                    sr = w.sampleRate;
                }
                OnlineStream stream = rec.createStream();
                stream.acceptWaveform(samples, sr);
                // 末尾补 1 秒静音，把最后一段推出来
                stream.acceptWaveform(new float[sr], sr);
                stream.inputFinished();
                while (rec.isReady(stream)) rec.decode(stream);
                OnlineRecognizerResult r = rec.getResult(stream);
                JSObject ret = new JSObject();
                ret.put("text", r != null ? r.getText() : "");
                ret.put("sampleRate", sr);
                call.resolve(ret);
            } catch (Throwable e) {
                Log.e(TAG, "feedWav 失败", e);
                call.reject("feedWav 失败: " + e.getMessage());
            }
        }, "sherpa-wav").start();
    }

    private static byte[] readAll(InputStream is) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toByteArray();
    }

    private static class Wav {
        float[] samples;
        int sampleRate;
    }

    /** 极简 WAV 解析：只认 PCM 16bit，取 data 段 */
    private static Wav parseWav(byte[] b) throws IOException {
        if (b.length < 44 || b[0] != 'R' || b[1] != 'I' || b[2] != 'F' || b[3] != 'F') {
            throw new IOException("不是 WAV 文件");
        }
        int pos = 12, sampleRate = 16000, channels = 1, bits = 16, dataOff = -1, dataLen = 0;
        while (pos + 8 <= b.length) {
            String id = new String(b, pos, 4, "US-ASCII");
            int size = (b[pos + 4] & 0xff) | ((b[pos + 5] & 0xff) << 8)
                    | ((b[pos + 6] & 0xff) << 16) | ((b[pos + 7] & 0xff) << 24);
            if ("fmt ".equals(id)) {
                channels = (b[pos + 10] & 0xff) | ((b[pos + 11] & 0xff) << 8);
                sampleRate = (b[pos + 12] & 0xff) | ((b[pos + 13] & 0xff) << 8)
                        | ((b[pos + 14] & 0xff) << 16) | ((b[pos + 15] & 0xff) << 24);
                bits = (b[pos + 22] & 0xff) | ((b[pos + 23] & 0xff) << 8);
            } else if ("data".equals(id)) {
                dataOff = pos + 8;
                dataLen = size;
                break;
            }
            pos += 8 + size + (size % 2);
        }
        if (dataOff < 0) throw new IOException("WAV 里找不到 data 段");
        if (bits != 16) throw new IOException("只支持 16bit WAV，实际 " + bits);
        dataLen = Math.min(dataLen, b.length - dataOff);
        int frames = dataLen / 2 / Math.max(channels, 1);
        float[] out = new float[frames];
        for (int i = 0; i < frames; i++) {
            int idx = dataOff + i * channels * 2;
            short v = (short) ((b[idx] & 0xff) | (b[idx + 1] << 8));
            out[i] = v / 32768.0f;
        }
        Wav w = new Wav();
        w.samples = out;
        w.sampleRate = sampleRate;
        return w;
    }
}
