package com.bg7yoz.ft8cn.ft8listener;
/**
 * 用于监听音频的类。监听通过时钟UtcTimer来控制周期，通过OnWaveDataListener接口来读取音频数据。
 *
 * @author BGY70Z
 * @date 2023-03-20
 */

import android.util.Log;

import androidx.lifecycle.MutableLiveData;

import com.bg7yoz.ft8cn.ft8signal.FT8Common;
import com.bg7yoz.ft8cn.ft8signal.Ft8Message;
import com.bg7yoz.ft8cn.GeneralVariables;
import com.bg7yoz.ft8cn.database.DatabaseOpr;
import com.bg7yoz.ft8cn.ft8transmit.GenerateFT8;
import com.bg7yoz.ft8cn.timer.OnUtcTimer;
import com.bg7yoz.ft8cn.timer.UtcTimer;
import com.bg7yoz.ft8cn.wave.OnGetVoiceDataDone;
import com.bg7yoz.ft8cn.wave.WaveFileReader;
import com.bg7yoz.ft8cn.wave.WaveFileWriter;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

public class FT8SignalListener {
    private static final String TAG = "FT8SignalListener";
    private final UtcTimer utcTimer;
    //private HamRecorder hamRecorder;
    private final OnFt8Listen onFt8Listen;//当开始监听，解码结束后触发的事件
    //private long band;
    public MutableLiveData<Long> decodeTimeSec = new MutableLiveData<>();//解码的时长
    public long timeSec=0;//解码的时长

    private OnWaveDataListener onWaveDataListener;


    private DatabaseOpr db;

    private final A91List a91List = new A91List();//a91列表

    //前回周期のデコードがまだ終わっていない場合に、新しいデコードとの重なり（CPU競合）を防ぐ。
    //デコードが遅れて次のスロットと重なると、両方の周期のデコード性能が劣化するため、
    //古い周期を優先完了させ、新しい周期はスキップする。
    private final AtomicBoolean decodeInProgress = new AtomicBoolean(false);


    static {
        System.loadLibrary("ft8cn");
    }

    public interface OnWaveDataListener {
        void getVoiceData(int duration, boolean afterDoneRemove, OnGetVoiceDataDone getVoiceDataDone);
    }

    public FT8SignalListener(DatabaseOpr db, OnFt8Listen onFt8Listen) {
        //this.hamRecorder = hamRecorder;
        this.onFt8Listen = onFt8Listen;
        this.db = db;

        //创建动作触发器，与UTC时间同步，以15秒一个周期，DoOnSecTimer是在周期起始时触发的事件。150是15秒
        utcTimer = new UtcTimer(FT8Common.FT8_SLOT_TIME_M, false, new OnUtcTimer() {
            @Override
            public void doHeartBeatTimer(long utc) {//不触发时的时钟信息
            }

            @Override
            public void doOnSecTimer(long utc) {//当指定间隔时触发时
                Log.d(TAG, String.format("触发录音,%d", utc));
                runRecorde(utc);
            }
        });
    }

    public void startListen() {
        utcTimer.start();
    }

    public void stopListen() {
        utcTimer.stop();
    }

    public boolean isListening() {
        return utcTimer.isRunning();
    }

    /**
     * 获取当前时间的偏移量，这里包括总的时钟偏移，也包括本实例的偏移
     *
     * @return int
     */
    public int time_Offset() {
        return utcTimer.getTime_sec() + UtcTimer.delay;
    }

    /**
     * 录音。在后台以多线程的方式录音，录音自动生成一个临时的Wav格式文件。
     * 有两个回调函数，用于开始录音时和结束录音时。当结束录音时，激活解码程序。
     *
     * @param utc 当前解码的UTC时间
     */
    private void runRecorde(long utc) {
        Log.d(TAG, "开始录音...");

        if (onWaveDataListener != null) {
            onWaveDataListener.getVoiceData(FT8Common.FT8_SLOT_TIME_MILLISECOND, true
                    , new OnGetVoiceDataDone() {
                        @Override
                        public void onGetDone(float[] data) {
                            Log.d(TAG, "开始解码...###");
                            decodeFt8(utc, data);
                        }
                    });
        }
    }

    public void decodeFt8(long utc, float[] voiceData) {
        //前回の周期のデコードが完了していない場合は、今回の周期をスキップする。
        //並行してデコードを実行するとCPUが競合し、両方の周期の同期検出・LDPC反復が遅延して
        //デコード性能（取りこぼし）が悪化するため、重複実行を防ぐ。
        if (!decodeInProgress.compareAndSet(false, true)) {
            Log.w(TAG, "前回のデコードが未完了のため、今回の周期はスキップします");
            return;
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    long time = System.currentTimeMillis();
                    if (onFt8Listen != null) {
                        onFt8Listen.beforeListen(utc);
                    }

                    //オーディオの前処理：DCオフセット除去＋安全な振幅正規化。
                    //録音デバイス（USBオーディオ、SCO等）によってDCオフセットや入力レベルが大きく異なり、
                    //そのままでは同期相関の閾値判定が不安定になる。ここで整えることで弱信号の検出率が上がる。
                    float[] pcm = preprocessAudio(voiceData);

                    ///读入音频数据，并做预处理
                    //其实这种方式要注意一个问题，在一个周期之内，必须解码完毕，否则新的解码又要开始了
                    long ft8Decoder = InitDecoder(utc, FT8Common.SAMPLE_RATE
                            , pcm.length, true);
                    DecoderMonitorPressFloat(pcm, ft8Decoder);//读入音频数据

                    ArrayList<Ft8Message> allMsg = new ArrayList<>();
                    ArrayList<Ft8Message> msgs = runDecode(ft8Decoder, utc, false);
                    addMsgToList(allMsg, msgs);
                    timeSec = System.currentTimeMillis() - time;
                    decodeTimeSec.postValue(timeSec);//解码耗时
                    if (onFt8Listen != null) {
                        onFt8Listen.afterDecode(utc, averageOffset(allMsg), UtcTimer.sequential(utc), msgs, false);
                    }


                    if (GeneralVariables.deepDecodeMode) {//进入深度解码模式
                        //高速パスで解けた強い信号を先に差し引く（サブトラクティブ・デコード）。
                        //強信号に隠れた弱信号が、最初のディープパスから見えるようになり、
                        //弱信号の取りこぼしを減らせる（JTAlert/WSJT-X系のサブトラクション相当）。
                        if (a91List.size() > 0
                                && timeSec <= FT8Common.DEEP_DECODE_TIMEOUT) {
                            ReBuildSignal.subtractSignal(ft8Decoder, a91List);
                        }

                        msgs = runDecode(ft8Decoder, utc, true);
                        addMsgToList(allMsg, msgs);
                        timeSec = System.currentTimeMillis() - time;
                        decodeTimeSec.postValue(timeSec);//解码耗时
                        if (onFt8Listen != null) {
                            onFt8Listen.afterDecode(utc, averageOffset(allMsg), UtcTimer.sequential(utc), msgs, true);
                        }

                        do {
                            if (timeSec > FT8Common.DEEP_DECODE_TIMEOUT) break;//此处做超时检测，超过一定时间(7秒)，就不做减码操作了
                            //减去解码的信号
                            ReBuildSignal.subtractSignal(ft8Decoder, a91List);

                            //再做一次解码
                            msgs = runDecode(ft8Decoder, utc, true);
                            addMsgToList(allMsg, msgs);
                            timeSec = System.currentTimeMillis() - time;
                            decodeTimeSec.postValue(timeSec);//解码耗时
                            if (onFt8Listen != null) {
                                onFt8Listen.afterDecode(utc, averageOffset(allMsg), UtcTimer.sequential(utc), msgs, true);
                            }

                        } while (msgs.size() > 0);

                    }
                    //移到finalize() 方法中调用了
                    DeleteDecoder(ft8Decoder);

                    Log.d(TAG, String.format("解码耗时:%d毫秒", System.currentTimeMillis() - time));
                } finally {
                    decodeInProgress.set(false);
                }
            }
        }).start();
    }

    /**
     * オーディオ前処理。FT8の同期・復号の前段で、入力信号の質を整える。
     * <ul>
     *     <li>DCオフセット除去：入力の平均値を引く。USBサウンドデバイス等で混入するDC成分は
     *         低周波の相関ピークを汚し、同期候補の検出を不安定にする。</li>
     *     <li>振幅正規化：あまりに小さい入力だけを持ち上げる（ゲイン上限つき）。
     *         過大入力はクリップに近いので触らず、小さすぎる信号のみ底上げして弱信号の検出を助ける。
     *         増幅し過ぎるとノイズも持ち上がるため、ゲインには上限を設ける。</li>
     * </ul>
     * 注意：FT8の周波数同期・LDPC自体はlibft8cn.so内で行われるため、ここでは位相や時間を変えず、
     * 無害なスカラー処理のみを行う。
     *
     * @param data 生のPCMデータ（-1.0〜1.0想定）
     * @return 前処理後のPCMデータ。入力が空/不正ならそのまま返す。
     */
    private float[] preprocessAudio(float[] data) {
        if (data == null || data.length == 0) return data;

        //1) DCオフセット除去
        double sum = 0;
        for (float v : data) sum += v;
        float mean = (float) (sum / data.length);

        //2) ピーク振幅を測定（絶対値の最大）
        float peak = 0f;
        for (float v : data) {
            float a = Math.abs(v - mean);
            if (a > peak) peak = a;
        }
        if (peak < 1e-6f) return data;//無音相当。何もしない。

        //3) 小さい信号のみ持ち上げる。目標ピーク0.5、増幅上限8倍。
        //   すでに十分な振幅がある(>0.5)場合は触らない（クリップや過増幅を避ける）。
        final float targetPeak = 0.5f;
        final float maxGain = 8.0f;
        float gain = 1.0f;
        if (peak < targetPeak) {
            gain = Math.min(targetPeak / peak, maxGain);
        }
        if (Math.abs(mean) < 1e-5f && gain == 1.0f) {
            return data;//処理不要
        }

        float[] out = new float[data.length];
        for (int i = 0; i < data.length; i++) {
            out[i] = (data[i] - mean) * gain;
        }
        return out;
    }


    private ArrayList<Ft8Message> runDecode(long ft8Decoder, long utc, boolean isDeep) {
        ArrayList<Ft8Message> ft8Messages = new ArrayList<>();
        Ft8Message ft8Message = new Ft8Message(FT8Common.FT8_MODE);

        ft8Message.utcTime = utc;
        ft8Message.band = GeneralVariables.band;
        a91List.clear();

        setDecodeMode(ft8Decoder, isDeep);//设置迭代次数,isDeep==true，迭代次数增加

        int num_candidates = DecoderFt8FindSync(ft8Decoder);//最多120个
        //long startTime = System.currentTimeMillis();
        for (int idx = 0; idx < num_candidates; ++idx) {
            //todo 应当做一下超时计算
            try {//做一下解码失败保护
                if (DecoderFt8Analysis(idx, ft8Decoder, ft8Message)) {

                    if (ft8Message.isValid) {
                        Ft8Message msg = new Ft8Message(ft8Message);//此处使用msg，是因为有的哈希呼号会把<...>替换掉
                        byte[] a91 = DecoderGetA91(ft8Decoder);
                        a91List.add(a91, ft8Message.freq_hz, ft8Message.time_sec);

                        if (checkMessageSame(ft8Messages, msg)) {
                            continue;
                        }

                        msg.isWeakSignal = isDeep;//是不是弱信号
                        ft8Messages.add(msg);

                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "run: " + e.getMessage());
            }

        }


        return ft8Messages;
    }

    /**
     * 计算平均时间偏移值
     *
     * @param messages 消息列表
     * @return 偏移值
     */
    private float averageOffset(ArrayList<Ft8Message> messages) {
        if (messages.size() == 0) return 0f;
        float dt = 0;
        //int dtAverage = 0;
        for (Ft8Message msg : messages) {
            dt += msg.time_sec;
        }
        return dt / messages.size();
    }

    /**
     * 把消息添加到列表中
     *
     * @param allMsg 消息列表
     * @param newMsg 新的消息
     */
    private void addMsgToList(ArrayList<Ft8Message> allMsg, ArrayList<Ft8Message> newMsg) {
        for (int i = newMsg.size() - 1; i >= 0; i--) {
            if (checkMessageSame(allMsg, newMsg.get(i))) {
                newMsg.remove(i);
            } else {
                allMsg.add(newMsg.get(i));
            }
        }
    }

    /**
     * 检查消息列表里同样的内容是否存在
     *
     * @param ft8Messages 消息列表
     * @param ft8Message  消息
     * @return boolean
     */
    private boolean checkMessageSame(ArrayList<Ft8Message> ft8Messages, Ft8Message ft8Message) {
        for (Ft8Message msg : ft8Messages) {
            if (msg.getMessageText().equals(ft8Message.getMessageText())) {
                if (msg.snr < ft8Message.snr) {
                    msg.snr = ft8Message.snr;
                }
                return true;
            }
        }
        return false;
    }

    @Override
    protected void finalize() throws Throwable {
        //DeleteDecoder(ft8Decoder);
        super.finalize();
    }

    public OnWaveDataListener getOnWaveDataListener() {
        return onWaveDataListener;
    }

    public void setOnWaveDataListener(OnWaveDataListener onWaveDataListener) {
        this.onWaveDataListener = onWaveDataListener;
    }


    public String getCacheFileName(String fileName) {
        return GeneralVariables.getMainContext().getCacheDir() + "/" + fileName;
    }

    public float[] ints2floats(int data[][]) {
        float temp[] = new float[data[0].length];
        for (int i = 0; i < data[0].length; i++) {
            temp[i] = data[0][i] / 32768.0f;
        }
        return temp;
    }

    public int[] floats2ints(float data[]) {
        int temp[] = new int[data.length];
        for (int i = 0; i < data.length; i++) {
            temp[i] = (int) (data[i] * 32767.0f);
        }
        return temp;
    }

    /**
     * 解码的第一步，初始化解码器，获取解码器的地址。
     *
     * @param utcTime     UTC时间
     * @param sampleRat   采样率，12000
     * @param num_samples 缓冲区数据的长度
     * @param isFt8       是否是FT8信号
     * @return 返回解码器的地址
     */
    public native long InitDecoder(long utcTime, int sampleRat, int num_samples, boolean isFt8);

    /**
     * 解码的第二步，读取Wav数据。
     *
     * @param buffer  Wav数据缓冲区
     * @param decoder 解码器数据的地址
     */
    public native void DecoderMonitorPress(int[] buffer, long decoder);

    public native void DecoderMonitorPressFloat(float[] buffer, long decoder);


    /**
     * 解码的第三步，同步数据。
     *
     * @param decoder 解码器地址
     * @return 中标信号的数量
     */
    public native int DecoderFt8FindSync(long decoder);

    /**
     * 解码的第四步，分析出消息。（需要在一个循环里）
     *
     * @param idx        中标信号的序号
     * @param decoder    解码器的地址
     * @param ft8Message 解出来的消息
     * @return boolean
     */
    public native boolean DecoderFt8Analysis(int idx, long decoder, Ft8Message ft8Message);

    /**
     * 解码的最后一步，删除解码器数据
     *
     * @param decoder 解码器数据的地址
     */
    public native void DeleteDecoder(long decoder);

    public native void DecoderFt8Reset(long decoder, long utcTime, int num_samples);

    public native byte[] DecoderGetA91(long decoder);//获取当前message的a91数据

    public native void setDecodeMode(long decoder, boolean isDeep);//设置解码的模式，isDeep=true是多次迭代，=false是快速迭代
}
