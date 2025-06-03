package com.bg7yoz.ft8cn;

/**
 * Constants related to FT8 and FT4 communication modes.
 * These constants define various timing, sampling, and operational parameters
 * used throughout the application for signal processing and communication scheduling.
 * @author BGY70Z
 * @date 2023-03-20
 */
public final class FT8Common {
    /**
     * Identifier for FT8 mode. Used to differentiate FT8 operations from other modes like FT4.
     */
    public static final int FT8_MODE=0;
    /**
     * Identifier for FT4 mode. Used to differentiate FT4 operations from other modes like FT8.
     */
    public static final int FT4_MODE=1;
    /**
     * Audio sample rate in Hz. This is a common sample rate used for FT8/FT4 signal processing.
     */
    public static final int SAMPLE_RATE=12000;
    /**
     * Duration of an FT8 transmission slot in seconds. FT8 transmissions occur in 15-second intervals.
     */
    public static final int FT8_SLOT_TIME=15;
    /**
     * Duration of an FT8 transmission slot in milliseconds. (15 seconds * 1000 ms/second).
     * This is used for precise timing calculations.
     */
    public static final int FT8_SLOT_TIME_MILLISECOND=15000;
    /**
     * Duration of an FT4 transmission slot in milliseconds. FT4 has shorter slots than FT8 (7.5 seconds * 1000 ms/second).
     */
    public static final int FT4_SLOT_TIME_MILLISECOND=7500;
    /**
     * Duration in milliseconds required for the transmission of 5 FT8 symbols.
     * This timing is relevant for understanding sub-slot timings or specific parts of the FT8 signal structure.
     * Note: The original comment "5个符号所需的" means "required for 5 symbols".
     */
    public static final int FT8_5_SYMBOLS_MILLISECOND=800;

    /**
     * Duration of an FT4 transmission slot in seconds (float value). FT4 slots are 7.5 seconds long.
     */
    public static final float FT4_SLOT_TIME=7.5f;
    /**
     * Duration of an FT8 transmission slot in tenths of a second (15 seconds * 10).
     * This unit might be used in specific internal calculations or displays.
     * Original comment "15秒" means "15 seconds".
     */
    public static final int FT8_SLOT_TIME_M=150;
    /**
     * Duration for 5 FT8 symbols in tenths of a second (0.8 seconds * 10).
     * Original comment "5个符号的时间长度0.8秒" means "time length of 5 symbols is 0.8 seconds".
     */
    public static final int FT8_5_SYMBOLS_TIME_M =8;
    /**
     * Duration of an FT4 transmission slot in tenths of a second (7.5 seconds * 10).
     * Original comment "7.5秒" means "7.5 seconds".
     */
    public static final int FT4_SLOT_TIME_M=75;
    /**
     * Default transmission delay in milliseconds. This delay can be added before starting a transmission
     * to account for radio switching time or other latencies.
     * Original comment "默认发射延迟时长，毫秒" means "default transmission delay duration, milliseconds".
     */
    public static final int FT8_TRANSMIT_DELAY=500;
    /**
     * Maximum time allocated for a deep decoding attempt in milliseconds (7 seconds).
     * This timeout prevents decoding processes from running indefinitely.
     * Original comment "深度解码的最长时间范围" means "maximum time range for deep decoding".
     */
    public static final long DEEP_DECODE_TIMEOUT=7*1000;
    /**
     * Number of iterations for the decoding algorithm.
     * In some FT8 decoders, multiple passes or iterations might be used to improve decoding success,
     * though here it's set to 1, implying a single-pass decoding strategy by default.
     * Original comment "迭代次数" means "number of iterations".
     */
    public static final int DECODE_MAX_ITERATIONS=1;
}
