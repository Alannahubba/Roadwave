package kr.onradio.player;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Base64;
import java.util.zip.CRC32;

/**
 * Java adaptation of the MIT-licensed shazamio-core spectrogram and signature format.
 * Copyright (c) 2024 dotX12. See ShazamIO-core-MIT.txt in the app assets.
 * https://github.com/shazamio/shazamio-core/tree/6f4f9f4c07aa01f17391d5322a990da5b27d53c5
 */
final class AudioFingerprint {
    static final int RATE = 16_000;
    private static final int FRAME = 2048;
    private static final int HOP = 128;
    private static final int BINS = FRAME / 2 + 1;
    private static final int HISTORY = 256;
    private static final int[] SPREAD_LAGS = {1, 3, 6};
    private static final int[] FREQUENCY_NEIGHBORS = {2, 3, 6, 9};
    private static final int[] TIME_NEIGHBORS = {4, 7, 14, 21, 28, 35, 42};
    private final float[][] power = new float[HISTORY][BINS];
    private final float[][] spread = new float[HISTORY][BINS];
    private final short[] frame = new short[FRAME];
    private final float[] real = new float[FRAME];
    private final float[] imaginary = new float[FRAME];
    private final float[] window = new float[FRAME];
    private final float[] cos = new float[FRAME / 2];
    private final float[] sin = new float[FRAME / 2];
    private final int[] reversed = new int[FRAME];
    private final ByteArrayOutputStream[] bands = new ByteArrayOutputStream[4];
    private final int[] lastPeak = new int[4];
    private int count;

    private AudioFingerprint() {
        for (int i = 0; i < FRAME; i++) {
            window[i] = (float) Math.pow(Math.sin(Math.PI * (i + 1) / (FRAME + 1)), 2);
            reversed[i] = Integer.reverse(i) >>> (32 - Integer.numberOfTrailingZeros(FRAME));
        }
        for (int i = 0; i < cos.length; i++) {
            double angle = -2 * Math.PI * i / FRAME;
            cos[i] = (float) Math.cos(angle);
            sin[i] = (float) Math.sin(angle);
        }
        Arrays.setAll(bands, i -> new ByteArrayOutputStream());
    }

    static String create(short[] samples, int sourceRate) {
        short[] mono = sourceRate == RATE ? samples : resample(samples, sourceRate);
        AudioFingerprint job = new AudioFingerprint();
        for (int offset = 0; offset + HOP <= mono.length; offset += HOP) job.push(mono, offset);
        return "data:audio/vnd.shazam.sig;base64," + Base64.getEncoder().encodeToString(job.encode(mono.length));
    }

    private static short[] resample(short[] pcm, int rate) {
        short[] result = new short[(int) ((long) pcm.length * RATE / rate)];
        double cutoff = Math.min(1.0, RATE / (double) rate) * .94;
        double scale = rate / (double) RATE;
        final int radius = 16;
        for (int i = 0; i < result.length; i++) {
            double at = i * scale;
            int center = (int) at;
            double sum = 0, weight = 0;
            for (int j = Math.max(0, center - radius + 1);
                    j <= Math.min(pcm.length - 1, center + radius); j++) {
                double distance = at - j;
                double coefficient = cutoff * sinc(distance * cutoff) * sinc(distance / radius);
                sum += pcm[j] * coefficient;
                weight += coefficient;
            }
            result[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(sum / weight)));
        }
        return result;
    }

    private static double sinc(double value) {
        return Math.abs(value) < 1e-9 ? 1 : Math.sin(Math.PI * value) / (Math.PI * value);
    }

    private int slot(int lag) {
        return (count + HISTORY - 1 - lag) % HISTORY;
    }

    private void push(short[] samples, int offset) {
        System.arraycopy(frame, HOP, frame, 0, FRAME - HOP);
        System.arraycopy(samples, offset, frame, FRAME - HOP, HOP);
        count++;
        float[] energy = power[slot(0)];
        float[] expanded = spread[slot(0)];
        transform();
        for (int i = 0; i < BINS; i++) {
            energy[i] = Math.max((real[i] * real[i] + imaginary[i] * imaginary[i]) / 131072f, 1e-10f);
        }
        System.arraycopy(energy, 0, expanded, 0, BINS);
        for (int i = 0; i < BINS - 2; i++) {
            expanded[i] = Math.max(energy[i], Math.max(energy[i + 1], energy[i + 2]));
        }
        for (int lag : SPREAD_LAGS) {
            float[] earlier = spread[slot(lag)];
            for (int i = 0; i < BINS; i++) earlier[i] = Math.max(earlier[i], expanded[i]);
        }
        if (count > 45) collectPeaks();
    }

    private void transform() {
        Arrays.fill(imaginary, 0);
        for (int i = 0; i < FRAME; i++) real[reversed[i]] = frame[i] * window[i];
        for (int length = 2; length <= FRAME; length <<= 1) {
            int half = length / 2;
            int stride = FRAME / length;
            for (int base = 0; base < FRAME; base += length) {
                for (int j = 0; j < half; j++) {
                    int a = base + j, b = a + half, k = j * stride;
                    float re = real[b] * cos[k] - imaginary[b] * sin[k];
                    float im = real[b] * sin[k] + imaginary[b] * cos[k];
                    real[b] = real[a] - re;
                    imaginary[b] = imaginary[a] - im;
                    real[a] += re;
                    imaginary[a] += im;
                }
            }
        }
    }

    private void collectPeaks() {
        int index = count - 46;
        float[] candidate = power[slot(45)];
        float[] reference = spread[slot(48)];
        bins:
        for (int bin = 10; bin <= 1014; bin++) {
            float energy = candidate[bin];
            int below = bin - 1;
            if (energy < 1f / 64 || energy < reference[below]) continue;
            for (int distance : FREQUENCY_NEIGHBORS) {
                if (energy <= reference[below - distance] || energy <= reference[below + distance]) continue bins;
            }
            for (int distance : TIME_NEIGHBORS) {
                if (energy <= spread[slot(48 - distance)][below]
                        || energy <= spread[slot(48 + distance)][below]) continue bins;
            }
            float magnitude = magnitude(energy);
            float left = magnitude(candidate[bin - 1]), right = magnitude(candidate[bin + 1]);
            float curvature = 2 * magnitude - left - right;
            int refined = bin * 64 + (curvature == 0 ? 0 : (int) ((right - left) * 32 / curvature));
            int frequency = refined * 125 / 1024;
            if (frequency < 250 || frequency > 5500) continue;
            int band = frequency < 520 ? 0 : frequency < 1450 ? 1 : frequency < 3500 ? 2 : 3;
            ByteArrayOutputStream output = bands[band];
            int delta = index - lastPeak[band];
            if (delta >= 255) {
                output.write(255);
                writeLittleEndian(output, index, 4);
                delta = 0;
            }
            output.write(delta);
            writeLittleEndian(output, (int) magnitude, 2);
            writeLittleEndian(output, refined, 2);
            lastPeak[band] = index;
        }
    }

    private static float magnitude(float power) {
        return Math.max((float) Math.log(power), 1f / 64) * 1477.3f + 6144;
    }

    private byte[] encode(int sampleCount) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (int i = 0; i < bands.length; i++) {
            byte[] peaks = bands[i].toByteArray();
            if (peaks.length == 0) continue;
            writeLittleEndian(body, 0x60030040 + i, 4);
            writeLittleEndian(body, peaks.length, 4);
            body.write(peaks, 0, peaks.length);
            while (body.size() % 4 != 0) body.write(0);
        }
        int length = body.size() + 8;
        ByteBuffer result = ByteBuffer.allocate(48 + length).order(ByteOrder.LITTLE_ENDIAN);
        int[] fields = {0xcafe2580, 0, length, 0x94119c00, 0, 0, 0, 3 << 27, 0, 0,
                sampleCount + 3840, 0x007c0000, 0x40000000, length};
        for (int field : fields) result.putInt(field);
        result.put(body.toByteArray());
        CRC32 crc = new CRC32();
        crc.update(result.array(), 8, result.capacity() - 8);
        result.putInt(4, (int) crc.getValue());
        return result.array();
    }

    private static void writeLittleEndian(ByteArrayOutputStream output, int value, int bytes) {
        for (int i = 0; i < bytes; i++) output.write(value >>> (i * 8) & 255);
    }
}
